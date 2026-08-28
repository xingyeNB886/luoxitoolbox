package com.sukisu.ultra.ui.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * 权限授权类型
 */
enum class PermissionGrantType {
    /** 未授权 */
    NONE,
    /** Shizuku (adb shell) 授权 */
    ADB,
    /** Root 授权 */
    ROOT,
    /** 两者都已授权（优先显示 ROOT） */
    BOTH;

    val isWorking: Boolean get() = this != NONE

    val displayLabel: String
        get() = when (this) {
            ADB -> "adb"
            ROOT -> "root"
            BOTH -> "root"
            NONE -> ""
        }
}

/**
 * 洛茜工具箱 · 权限管理器
 *
 * 策略：
 *   1. Manifest 声明 ShizukuProvider（SDK 内部自动初始化）
 *   2. Application.onCreate 里安装 Binder 监听，授权状态变化时通知 UI 刷新
 *   3. pingBinder / checkSelfPermission / requestPermission 全部走 Shizuku 官方 API
 */
object PermissionManager {

    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    private var listenersInstalled = false

    fun installListenersIfNeeded() {
        if (listenersInstalled) return
        synchronized(this) {
            if (listenersInstalled) return
            runCatching {
                val received = Shizuku.OnBinderReceivedListener {
                    notifyChanged("binder_received")
                }
                Shizuku.addBinderReceivedListener(received)

                val dead = Shizuku.OnBinderDeadListener {
                    notifyChanged("binder_dead")
                }
                Shizuku.addBinderDeadListener(dead)
            }
            listenersInstalled = true
        }
    }

    fun addOnChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeOnChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged(why: String) {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { l -> runCatching { l() } }
    }

    // ---------- 权限检测 ----------

    fun isRootGranted(): Boolean =
        runCatching { rootAvailable() }.getOrDefault(false)

    fun isShizukuGranted(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权（直接调用 SDK 原生接口）。
     * @return true = 已经授权 / 老版本 Shizuku 无需显式授权
     *         false = 弹框等待用户选择 / Shizuku 服务未运行
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true
            }
            Shizuku.requestPermission(requestCode)
            false
        }.getOrDefault(false)
    }

    /**
     * 轮询检测授权状态（每 2s 一次，最多 n 轮）。
     *
     * 场景：requestPermission 弹了授权框，用户在弹框里点了允许/拒绝后，
     * OnRequestPermissionResultListener 回调 → 刷新。
     * 但如果用户切到 Shizuku App 里手动授权（没走弹框），这个轮询就是兜底。
     */
    fun shizukuGrantPollingFlow(maxRounds: Int = 60): Flow<Boolean> = flow {
        repeat(maxRounds) {
            val now = isShizukuGranted()
            emit(now)
            notifyChanged("polling")
            if (now) return@flow
            delay(2000)
        }
    }

    fun notifyPermissionRequestResult() = notifyChanged("permission_result")

    suspend fun checkGrantType(forceRefresh: Boolean = true): PermissionGrantType =
        withContext(Dispatchers.IO) {
            val root = isRootGranted()
            val shizuku = isShizukuGranted()
            when {
                root && shizuku -> PermissionGrantType.BOTH
                root -> PermissionGrantType.ROOT
                shizuku -> PermissionGrantType.ADB
                else -> PermissionGrantType.NONE
            }
        }

    fun invalidateCache() = notifyChanged("invalidate_cache")

    /**
     * 是否已任意一种权限（Root 或 Shizuku）授权 —— 用于首页 StatusCard
     * 判断是否"工作中"。
     */
    fun isAnyGranted(): Boolean = isRootGranted() || isShizukuGranted()

    /**
     * 当前授权类型的展示标签，用于首页 StatusCard 标题后缀，如 " <Root>"。
     * 同时授权时优先显示 ROOT。
     */
    fun getGrantLabel(): String {
        val root = isRootGranted()
        val shizuku = isShizukuGranted()
        return when {
            root -> "<root>"
            shizuku -> "<ADB>"
            else -> ""
        }
    }

    fun permissionChanges(): Flow<Unit> = callbackFlow {
        val listener: () -> Unit = { trySend(Unit) }
        addOnChangeListener(listener)
        awaitClose { removeOnChangeListener(listener) }
    }.conflate()

    // ---------- 命令执行（初始化用） ----------

    private const val EXEC_TIMEOUT_MS = 15_000L

    private val bindMutex = Mutex()

    @Volatile
    private var cachedService: com.sukisu.ultra.service.IShellService? = null

    /**
     * 通过 Root 或 Shizuku 执行 shell 命令。
     * Root → libsu；Shizuku → UserService（IShellService）。
     * @return 命令输出；无权限或命令失败（退出码非 0）返回 null
     */
    suspend fun execShell(cmd: String): String? = withContext(Dispatchers.IO) {
        val grant = checkGrantType()
        when {
            grant == PermissionGrantType.ROOT || grant == PermissionGrantType.BOTH -> {
                runCatching {
                    val result = Shell.cmd(cmd).exec()
                    if (result.isSuccess) result.out.joinToString("\n") else null
                }.getOrNull()
            }

            grant == PermissionGrantType.ADB -> {
                withTimeoutOrNull(EXEC_TIMEOUT_MS) { execWithShizuku(cmd) }
            }

            else -> null
        }
    }

    private suspend fun execWithShizuku(cmd: String): String? {
        cachedService?.let { s ->
            runCatching { return s.exec(cmd) }
            cachedService = null
        }
        return bindMutex.withLock {
            cachedService?.let { s ->
                runCatching { return@withLock s.exec(cmd) }
                cachedService = null
            }
            val s = bindShellService() ?: return@withLock null
            cachedService = s
            runCatching { s.exec(cmd) }.getOrNull()
        }
    }

    private suspend fun bindShellService(): com.sukisu.ultra.service.IShellService? =
        suspendCancellableCoroutine { cont ->
            if (!isShizukuGranted()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val args = Shizuku.UserServiceArgs(
                android.content.ComponentName(
                    com.sukisu.ultra.ksuApp,
                    com.sukisu.ultra.service.ShellService::class.java
                )
            )
                .processNameSuffix("shell")
                .version(1)

            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    val service = com.sukisu.ultra.service.IShellService.Stub.asInterface(binder)
                    cachedService = service
                    if (cont.isActive) cont.resume(service)
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    cachedService = null
                }
            }

            val bound = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
            if (!bound && cont.isActive) {
                cont.resume(null)
            }
        }

    /**
     * 执行初始化：创建 luoxi 目录、备份/文件输出/裁剪子目录和标记文件（幂等）。
     */
    suspend fun ensureInitFiles(): Boolean {
        val cmd = "mkdir -p '/storage/emulated/0/luoxi' " +
            "'/storage/emulated/0/luoxi/备份' " +
            "'/storage/emulated/0/luoxi/文件输出' " +
            "'/storage/emulated/0/luoxi/裁剪'; " +
            "touch '/storage/emulated/0/Android/data/.media_cache_index'"
        return execShell(cmd) != null
    }
}
