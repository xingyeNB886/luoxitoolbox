package me.weishu.kernelsu.ui.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

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

    /** 用于首页状态卡尖括号标签的显示文字 */
    val displayLabel: String
        get() = when (this) {
            ADB -> "adb"
            ROOT -> "root"
            BOTH -> "root"
            NONE -> ""
        }
}

/**
 * 权限检测管理器（Shizuku 授权实时检测版）
 *
 * 洛茜工具箱改造重点：
 *   - 不再声明 ShizukuProvider（避免 Provider 类加载期 SIGABRT 直接闪退）
 *   - 完全依赖 Shizuku.addBinderReceivedListener / OnBinderDiedListener 手动
 *     感知 Shizuku Binder 变化，任何变化都 invalidateCache 并通过 Flow 推给 UI
 *   - 授权状态 = pingBinder && (PreV11 || checkSelfPermission || 反射执行 id -u 成功)
 *   - 权限状态 Flow 用于首页实时更新，失效也会立刻推给 UI
 */
object PermissionManager {

    /** 永远不要缓存，实时才可靠 */
    private val listeners = mutableListOf<() -> Unit>()

    private val permissionChangedListeners = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
    private var binderReceived: Shizuku.OnBinderReceivedListener? = null
    private var binderDied: Shizuku.OnBinderDiedListener? = null

    @Volatile
    private var listenersInstalled = false

    /**
     * 安装 Shizuku Binder 监听 + 授权结果监听（主进程 Application.onCreate 里调用一次）。
     * 不依赖 ShizukuProvider，所有监听是最佳努力，失败了就算（也不会崩）。
     */
    fun installListenersIfNeeded() {
        if (listenersInstalled) return
        synchronized(this) {
            if (listenersInstalled) return
            runCatching {
                val received = Shizuku.OnBinderReceivedListener {
                    notifyChanged("binder_received")
                }
                val died = Shizuku.OnBinderDiedListener {
                    notifyChanged("binder_died")
                }
                Shizuku.addBinderReceivedListener(received)
                Shizuku.addBinderDiedListener(died)
                binderReceived = received
                binderDied = died
            }
            listenersInstalled = true
        }
    }

    /** 外部订阅：权限状态任何变化都会被调用 */
    fun addOnChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeOnChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged(why: String) {
        // 任何变化都让下一次 checkGrantType 重新测（即便有缓存也强制失效）
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { l ->
            runCatching { l() }
        }
    }

    // ---------- 权限检测 ----------

    /** 检测 Root 权限是否可用（基于 libsu） */
    fun isRootGranted(): Boolean {
        return runCatching { rootAvailable() }.getOrDefault(false)
    }

    /**
     * Shizuku 是否可用（已授权 + 真能执行命令）。
     * 检测顺序：
     *   1. pingBinder() —— Shizuku 服务都没启动 = 直接 false
     *   2. PreV11（老 Shizuku 不需要显式授权）—— 直接 true
     *   3. checkSelfPermission() == PERMISSION_GRANTED —— true
     *   4. 反射调用 Shizuku.newProcess("id -u") 返回 2000/0 —— true
     *  其它全部 false
     */
    fun isShizukuGranted(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            val sdkGranted = runCatching {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (sdkGranted) return true
            // 兜底：真的跑一条命令试试
            canExecuteShellIdViaShizuku()
        }.getOrDefault(false)
    }

    /**
     * 反射调用 rikka.shizuku.Shizuku#newProcess(cmd, env, dir)。
     * 只要执行成功且输出是 "2000" 或 "0"，说明 Shizuku 确实授予了 shell/root 身份。
     */
    private fun canExecuteShellIdViaShizuku(): Boolean {
        return runCatching {
            val shizukuClass = Shizuku::class.java
            val method = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                java.io.File::class.java
            )
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(
                null,
                arrayOf("id", "-u"),
                null,
                null
            ) as? Process ?: return false
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val uid = reader.readLine()?.trim()?.toIntOrNull()
            runCatching { reader.close() }
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
            uid == 2000 || uid == 0
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权（直接调 SDK 原接口，不做额外吞错误）。
     * @return true = 已经授权；false = 需要用户在弹出的对话框里点允许（结果会通过监听回调）
     *         或 Shizuku 没启动 / 请求权限失败
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true
            }
            // 直接调原 API，让它自己弹授权框
            runCatching { Shizuku.requestPermission(requestCode) }.getOrThrow()
            false
        }.getOrDefault(false)
    }

    /** 检查当前授权类型 */
    suspend fun checkGrantType(forceRefresh: Boolean = true): PermissionGrantType =
        withContext(Dispatchers.IO) {
            val root = isRootGranted()
            val shizuku = isShizukuGranted()
            val type = when {
                root && shizuku -> PermissionGrantType.BOTH
                root -> PermissionGrantType.ROOT
                shizuku -> PermissionGrantType.ADB
                else -> PermissionGrantType.NONE
            }
            type
        }

    /** 清除缓存（实际上当前没有缓存，保留用于兼容） */
    fun invalidateCache() {
        notifyChanged("invalidate_cache")
    }

    /**
     * 使用 Shizuku 执行特权命令（仅当 Shizuku 已授权时可用）
     */
    fun <T> withShizukuContext(block: () -> T): Result<T> {
        return runCatching {
            if (!isShizukuGranted()) error("Shizuku not granted")
            block()
        }
    }

    /** 检查目标包名的 Shizuku Provider 上下文（洛茜工具箱不用，保留空实现） */
    fun getShizukuUserServiceArgs(context: Context = ksuApp): Shizuku.UserServiceArgs? {
        return null
    }

    /** 把权限变化包装成 Flow，方便 Compose UI collectAsState */
    fun permissionChanges(): Flow<Unit> = callbackFlow {
        val listener: () -> Unit = {
            trySend(Unit)
        }
        addOnChangeListener(listener)
        awaitClose { removeOnChangeListener(listener) }
    }.conflate()
}
