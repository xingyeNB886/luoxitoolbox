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
 * 权限检测管理器（Shizuku 官方 API 接入版）
 *
 * v10 改造说明（终于和 Shizuku 官方 demo 的接入方式完全一致）：
 *   1. Manifest 里声明 SafeShizukuProvider（继承 rikka.shizuku.ShizukuProvider）
 *   2. KernelSUApplication.attachBaseContext 第一时间 HiddenApiBypass 豁免，
 *      保证 Provider.onCreate 期间反射 hidden API 不会触发 dalvik SIGABRT
 *   3. Shizuku.pingBinder() 连通性
 *      ↓
 *      isPreV11 = 老版 Shizuku 不需要显式授权
 *      ↓ 否则
 *      checkSelfPermission() == PERMISSION_GRANTED = 已授权
 *      ↓ 否则
 *      requestPermission() 弹官方授权框
 *      ↓
 *      OnRequestPermissionResultListener 回调 → invalidate 权限状态
 *
 *  不再使用任何"反射执行 id -u"的 hack 方式，和官方 demo 一模一样。
 */
object PermissionManager {

    private val listeners = mutableListOf<() -> Unit>()

    private var binderReceived: Shizuku.OnBinderReceivedListener? = null

    @Volatile
    private var listenersInstalled = false

    /**
     * 装 Shizuku 的 Binder 监听（Application.onCreate 主进程里调用一次）
     */
    fun installListenersIfNeeded() {
        if (listenersInstalled) return
        synchronized(this) {
            if (listenersInstalled) return
            runCatching {
                val received = Shizuku.OnBinderReceivedListener {
                    notifyChanged("binder_received")
                }
                Shizuku.addBinderReceivedListener(received)
                binderReceived = received
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
        copy.forEach { l ->
            runCatching { l() }
        }
    }

    // ---------- 权限检测（纯官方 API，无 hack）----------

    /** 检测 Root 权限是否可用（基于 libsu） */
    fun isRootGranted(): Boolean {
        return runCatching { rootAvailable() }.getOrDefault(false)
    }

    /**
     * Shizuku 是否已授权。
     *
     * v10：完全按 Shizuku SDK 原生接口判断，不做反射命令执行等旁门左道。
     *   pingBinder → PreV11 → checkSelfPermission 三步走。
     */
    fun isShizukuGranted(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            // Shizuku < 11：没有显式授权模型，binder 通就等于可用
            if (Shizuku.isPreV11()) return true
            // Shizuku ≥ 11：走原生 checkSelfPermission（依赖 ShizukuProvider 做校验）
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权（直接调用 SDK 原生接口）。
     *
     * @return true = 已经授权，无需再弹
     *         false = 弹框中 / Shizuku 服务没启动 / 请求失败
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true
            }
            // 直接用 SDK 原接口（SafeShizukuProvider 已声明后这里一定能正常工作）
            Shizuku.requestPermission(requestCode)
            false
        }.getOrDefault(false)
    }

    /** 授权结果：权限页收到 OnRequestPermissionResultListener 之后用这个推动 UI 刷新 */
    fun notifyPermissionRequestResult() {
        notifyChanged("permission_result")
    }

    /** 检查当前授权类型（永远实时重测，不缓存） */
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

    /** 强制无效化（UI 按钮点击后调） */
    fun invalidateCache() {
        notifyChanged("invalidate_cache")
    }

    fun <T> withShizukuContext(block: () -> T): Result<T> {
        return runCatching {
            if (!isShizukuGranted()) error("Shizuku not granted")
            block()
        }
    }

    /** 洛茜工具箱不用 UserService，始终返回 null */
    fun getShizukuUserServiceArgs(context: Context = ksuApp): Shizuku.UserServiceArgs? {
        return null
    }

    /** Compose UI 订阅权限变化的便捷 Flow（丢弃积压，只重绘最新一次） */
    fun permissionChanges(): Flow<Unit> = callbackFlow {
        val listener: () -> Unit = { trySend(Unit) }
        addOnChangeListener(listener)
        awaitClose { removeOnChangeListener(listener) }
    }.conflate()
}
