package me.weishu.kernelsu.ui.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import rikka.shizuku.Shizuku

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
 * Shizuku 权限管理器（v12 —— Shizuku.initialize 官方 API 版）
 *
 * 策略：
 *   1. Manifest 不声明 ShizukuProvider（避免 Provider 类加载期 native SIGABRT 闪退）
 *   2. Application.onCreate 里调用 Shizuku.initialize(this) 手动初始化 SDK
 *      （这是 Shizuku 官方接口，等价于 Provider.onCreate 的初始化逻辑，但不会在
 *       Provider 创建时机被系统 kill，因为它发生在 Application.onCreate 的
 *       普通 Java 调用链上，不是系统 Provider 生命周期）
 *   3. 之后 pingBinder / checkSelfPermission / requestPermission 全部走原生 API
 *   4. 没有任何反射 hack，和 Shizuku 官方 demo 完全一致
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
     * 请求 Shizuku 授权（直接调用 SDK 原生接口，无任何 hack）。
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
     * OnRequestPermissionResultListener 回调 → 刷新 UI。
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
     * 当前授权类型的展示标签，用于首页 StatusCard 标题后缀，如 " <ADB>"。
     * 同时授权时优先显示 ROOT。
     */
    fun getGrantLabel(): String {
        val root = isRootGranted()
        val shizuku = isShizukuGranted()
        return when {
            root -> "<Root>"
            shizuku -> "<ADB>"
            else -> ""
        }
    }

    fun <T> withShizukuContext(block: () -> T): Result<T> {
        return runCatching {
            if (!isShizukuGranted()) error("Shizuku not granted")
            block()
        }
    }

    fun getShizukuUserServiceArgs(context: Context = ksuApp): Shizuku.UserServiceArgs? = null

    fun permissionChanges(): Flow<Unit> = callbackFlow {
        val listener: () -> Unit = { trySend(Unit) }
        addOnChangeListener(listener)
        awaitClose { removeOnChangeListener(listener) }
    }.conflate()
}