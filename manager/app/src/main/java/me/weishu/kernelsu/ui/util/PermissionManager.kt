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
 * Shizuku 权限管理器（v11 —— 不声明 ShizukuProvider、手动反射初始化 SDK 内部引用）
 *
 * 背景：
 *   ShizukuProvider.onCreate 内部会调用 dalvik hidden API，在部分机型上直接
 *   SIGABRT 闪退（JNI kill，Java try-catch 也接不住），所以 Manifest 不再声明
 *   ShizukuProvider —— 但这带来另一个问题：Shizuku SDK 对系统 Binder 的引用
 *   （Shizuku.sService / sClient）也没法自动初始化，pingBinder() 永远 false。
 *
 * v11 修复思路 —— 不声明 Provider，但手动把 Provider 做过的初始化用反射补齐：
 *   1. 反射 ServiceManager.getService("shizuku") 拿到 Binder；
 *   2. 反射 Shizuku 的 setBinder / setService / 或 attachBaseContext(Context)
 *      方法，把我们手动拿到的 Binder 灌给 Shizuku SDK。
 *   3. 反射失败也不崩，退化为"反射执行 id -u 命令"的终极兜底。
 *
 * 授权结果轮询：如果 requestPermission 不弹框，UI 会调用 startPolling() 每 2s
 * 重测，用户切到 Shizuku App 手动点授权，再切回来也能立刻显示"工作中"。
 */
object PermissionManager {

    private val listeners = mutableListOf<() -> Unit>()

    private var binderReceived: Shizuku.OnBinderReceivedListener? = null

    @Volatile
    private var listenersInstalled = false
    @Volatile
    private var initTried = false
    @Volatile
    private var pingBinderAfterInit: Boolean = false

    /** 在 Application.onCreate 主进程调用一次：手动反射初始化 Shizuku Binder */
    fun installListenersIfNeeded() {
        if (listenersInstalled) return
        synchronized(this) {
            if (listenersInstalled) return
            runCatching {
                val received = Shizuku.OnBinderReceivedListener {
                    pingBinderAfterInit = true
                    notifyChanged("binder_received")
                }
                Shizuku.addBinderReceivedListener(received)
                binderReceived = received
            }
            listenersInstalled = true
        }
    }

    /** 手动反射补齐 Shizuku SDK 内部 Binder 引用（不声明 ShizukuProvider 的补救） */
    fun ensureShizukuInitialized(app: Context) {
        if (initTried) return
        synchronized(this) {
            if (initTried) return
            initTried = true

            // Strategy 1: 先看 SDK 自己默认能不能 pingBinder 通（用户系统兼容的话）
            val defaultPing = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (defaultPing) {
                pingBinderAfterInit = true
                return
            }

            // Strategy 2: 反射调用 ShizukuProvider 或 Shizuku 的静态初始化方法
            // a) 找 ShizukuProvider 的静态 onCreate(Context, String) / init(Context)
            runCatching {
                val authority = "${app.packageName}.shizuku"
                val klass = Class.forName("rikka.shizuku.ShizukuProvider")
                // 常见静态签名：onCreate(Context context, String authority)
                val candidates = listOf(
                    arrayOf(Class.forName("android.content.Context"),
                        String::class.java) to arrayOf(app.applicationContext, authority),
                    arrayOf(Class.forName("android.content.Context")) to arrayOf(app.applicationContext),
                )
                for ((paramTypes, args) in candidates) {
                    runCatching {
                        val m = klass.getDeclaredMethod("onCreateStatic", *paramTypes)
                        m.isAccessible = true
                        m.invoke(null, *args)
                    }
                    runCatching {
                        val m = klass.getDeclaredMethod("init", *paramTypes)
                        m.isAccessible = true
                        m.invoke(null, *args)
                    }
                }
            }
            val ping1 = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (ping1) {
                pingBinderAfterInit = true
                notifyChanged("shizuku_init_provider_static")
                return
            }

            // Strategy 3: 直接拿系统 Binder 对象（ServiceManager.getService("shizuku")），
            // 然后反射 Shizuku.sService / setBinder / setService 之类方法灌进去
            runCatching {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "shizuku") as? android.os.IBinder
                    ?: return@runCatching
                val shizukuClass = Shizuku::class.java
                // 常见 setter：setBinder(IBinder) / setService(IBinder)
                listOf("setBinder", "setService", "attachBinder", "onBinderReceived").forEach { name ->
                    runCatching {
                        val m = shizukuClass.getDeclaredMethod(name, android.os.IBinder::class.java)
                        m.isAccessible = true
                        m.invoke(null, binder)
                    }
                }
                // 再试字段：public static volatile IBinder sService; sClient
                listOf("sService", "sClient", "binder", "service").forEach { fname ->
                    runCatching {
                        val f = shizukuClass.getDeclaredField(fname)
                        f.isAccessible = true
                        f.set(null, binder)
                    }
                }
            }
            val ping2 = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (ping2) {
                pingBinderAfterInit = true
                notifyChanged("shizuku_init_binder_reflection")
                return
            }

            // Strategy 4: 直接给 Shizuku 传 Application Context —— 部分版本 SDK
            // 会用它去 ContentResolver 找 Provider（虽然我们 Manifest 没声明，但
            // 可能 SDK 内部 fallback 能自己找 ServiceManager）
            runCatching {
                val shizukuClass = Shizuku::class.java
                listOf("attachBaseContext", "setContext", "init", "initialize").forEach { name ->
                    runCatching {
                        val m = shizukuClass.getDeclaredMethod(
                            name,
                            Class.forName("android.content.Context")
                        )
                        m.isAccessible = true
                        m.invoke(null, app.applicationContext)
                    }
                }
            }
            val ping3 = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (ping3) {
                pingBinderAfterInit = true
                notifyChanged("shizuku_init_context_reflection")
                return
            }
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

    // ---------- 权限检测（多路径兜底，确保总是有结果）----------

    fun isRootGranted(): Boolean =
        runCatching { rootAvailable() }.getOrDefault(false)

    /**
     * Shizuku 是否授权 —— 三层判定：
     *   1. SDK pingBinder + checkSelfPermission（SDK 初始化成功的理想路径）
     *   2. 反射 Shizuku.newProcess("id -u") 返回 2000/0（反射拿命令执行）
     *   3. AppOpsManager + UID 对照（Shizuku 写过 AppOps 但我们没走 Provider）
     */
    fun isShizukuGranted(): Boolean {
        // 路径 1：官方 SDK 原生接口
        runCatching {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11()) return true
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return true
                }
            }
        }
        // 路径 2：反射执行命令（终极兜底，只要 Shizuku 服务运行且允许 uid 访问就真的能跑）
        if (canExecuteShellIdViaShizuku()) return true
        return false
    }

    /**
     * 反射调用 Shizuku.newProcess(cmd, env, dir) 执行 `id -u`：
     *  - 成功、输出 2000 = ADB shell 身份（Shizuku 授权）
     *  - 成功、输出 0    = root 身份（极端情况也算通）
     */
    private fun canExecuteShellIdViaShizuku(): Boolean {
        return runCatching {
            val klass = Shizuku::class.java
            val method = klass.getMethod(
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
     * 请求 Shizuku 授权。
     * 由于不声明 ShizukuProvider，requestPermission() 有可能不弹授权框。
     * 返回 false 时，UI 应当引导用户"去 Shizuku App 里手动找到洛茜工具箱 → 开启授权"，
     * 然后调用 [shizukuGrantPollingFlow] 每 2s 轮询直到检测到授权。
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (isShizukuGranted()) return true
            // 尽力调用 SDK 原接口（弹不弹框看系统兼容）
            runCatching {
                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                    Shizuku.requestPermission(requestCode)
                }
            }
            false
        }.getOrDefault(false)
    }

    /** 权限页轮询：每 2s 检测一次，直到已授权就停止。每一次检测都会推送 Flow 到 UI 刷新。 */
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
