package me.weishu.kernelsu

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.ui.crash.EarlyCrashHandler
import me.weishu.kernelsu.ui.crash.GlobalCrashHandler
import me.weishu.kernelsu.ui.util.CloudUpdateManager
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

@Volatile
private var _ksuApp: KernelSUApplication? = null

/**
 * 访问全局 Application 实例的安全入口。
 * 任何时候都返回非空；如果 Application 尚未就绪则抛出带明确提示的异常
 * （不会是无信息的 UninitializedPropertyAccessException）。
 */
val ksuApp: KernelSUApplication
    get() = _ksuApp
        ?: error("KernelSUApplication 尚未初始化（当前进程可能正处于 attachBaseContext 之前）。" +
                "请检查 EarlyCrashHandler 或 ContentProvider 中是否有代码过早引用 ksuApp。")

class KernelSUApplication : Application(), ViewModelStoreOwner {

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    /** 当前进程名（避免和 Application.getProcessName() JVM 签名冲突，不用 processName 命名） */
    private val currentProcessName: String by lazy {
        runCatching {
            val pid = android.os.Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
        }.getOrDefault(packageName)
    }

    /**
     * 是否是主进程（=包名本身）。
     * :error_report / :webui 等子进程中应跳过所有业务初始化，
     * 避免子进程里重复触发 Natives / SuperUser / Root Shell 等导致闪退。
     */
    private fun isMainProcess(): Boolean = currentProcessName == packageName

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        _ksuApp = this

        // 【第一优先】Hidden API 豁免（在系统创建 ContentProvider 之前！）
        // ShizukuProvider.onCreate 时会反射调用一些 @hide 系统 API，
        // 如果在此之前不调用 HiddenApiBypass 加豁免，在 Android 9+ 上
        // 会触发 dalvik 的 hidden-api 黑名单 policy → 直接 SIGABRT（非 Java
        // Throwable，try-catch 也接不住）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    // 整个 Shizuku 相关
                    "Lrikka/shizuku/",
                    // 系统 Binder/ServiceManager 相关
                    "Landroid/os/",
                    // 应用 / 包管理器隐藏接口
                    "Landroid/content/pm/",
                    // user handle / uid 相关
                    "Landroid/os/UserHandle;",
                    // 兜底：所有 L 前缀（相当于"允许所有 hidden API"）
                    "L"
                )
            }
        }
        EarlyCrashHandler.markStage("attachBaseContext_hiddenApiBypassDone")

        // 最早的异常捕获：只能做极简写文件操作，不可启动 Activity / 访问其他 SDK
        runCatching { EarlyCrashHandler.install(base ?: this) }
        EarlyCrashHandler.markStage("attachBaseContext", "pkg=${packageName}")
    }

    override fun onCreate() {
        EarlyCrashHandler.markStage("onCreate_start")
        try {
            super.onCreate()
            EarlyCrashHandler.markStage("onCreate_superCalled")

            // 如果是 :error_report / :webui 等子进程，到此为止，跳过所有业务初始化
            if (!isMainProcess()) {
                EarlyCrashHandler.markStage("onCreate_skipSubprocess", currentProcessName)
                return
            }
            EarlyCrashHandler.markStage("onCreate_mainProcess")

            // 主进程：防篡改签名校验
        runCatching {
            if (!CloudUpdateManager.verifyAppSignature(this)) {
                EarlyCrashHandler.markStage("onCreate_signatureInvalid")
                // 签名校验失败，标记为无效签名（由 HomePager 弹窗处理）
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("signature_invalid", true).apply()
            } else {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("signature_invalid", false).apply()
            }
        }
        EarlyCrashHandler.markStage("onCreate_signatureVerified")

        // 主进程：装上完整版异常处理器（会启动 NativeCrashActivity）
            runCatching { GlobalCrashHandler.init() }
            EarlyCrashHandler.markStage("onCreate_GlobalCrashHandlerReady")

            // 洛茜工具箱：安装 Shizuku Binder 监听（授权弹窗结果 / Binder 变化 → 推 Flow）
            // ShizukuProvider 已通过 Manifest 声明，SDK 内部自动初始化，无需手动 initialize
            runCatching {
                me.weishu.kernelsu.ui.util.PermissionManager.installListenersIfNeeded()
            }
            EarlyCrashHandler.markStage("onCreate_PermissionListenersReady")

            // 读上次崩溃 / 启动阶段日志 —— 由 MainActivity 首帧统一展示
            runCatching { EarlyCrashHandler.handlePendingCrash() }

            runCatching {
                val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
                superUserViewModel.loadAppList()
            }
            EarlyCrashHandler.markStage("onCreate_suVmLoaded")

            runCatching {
                val webroot = File(dataDir, "webroot")
                if (!webroot.exists()) {
                    webroot.mkdir()
                }
            }
            EarlyCrashHandler.markStage("onCreate_webrootReady")

            // Provide working env for rust's temp_dir()
            runCatching {
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
            }
            EarlyCrashHandler.markStage("onCreate_tmpdirSet")

            runCatching {
                okhttpClient =
                    OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                        .addInterceptor { block ->
                            block.proceed(
                                block.request().newBuilder()
                                    .header("User-Agent", "KernelSU/${BuildConfig.VERSION_CODE}")
                                    .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                            )
                        }.build()
            }
            EarlyCrashHandler.markStage("onCreate_okhttpReady")
            EarlyCrashHandler.markStage("onCreate_end")
        } catch (t: Throwable) {
            EarlyCrashHandler.markStage("onCreate_FATAL",
                "${t.javaClass.simpleName}: ${t.message}")
            val sw = java.io.StringWriter()
            java.io.PrintWriter(sw).use { t.printStackTrace(it) }
            val detail = sw.toString()
            runCatching {
                me.weishu.kernelsu.ui.crash.GlobalCrashHandler.writeCrashLogForThrowable(
                    this, t, "Application.onCreate 整体 try-catch"
                )
            }
            // 强制设 early pending，保证下次启动一定能看到
            runCatching {
                val ctx = this
                val pending = java.io.File(ctx.filesDir, "early_pending_crash.log")
                pending.writeText(
                    "=== Application.onCreate 异常（整体 try-catch 捕获） ===\n" +
                            "时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
                            "$detail\n"
                )
            }
            // 不重抛，不杀进程 —— 让系统继续走完 Application.onCreate
        }
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
