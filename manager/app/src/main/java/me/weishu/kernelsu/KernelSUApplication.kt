package me.weishu.kernelsu

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.crash.EarlyCrashHandler
import me.weishu.kernelsu.ui.crash.GlobalCrashHandler
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

/**
 * 非空保证版：对于仅在 UI 正常运行期间、或 runCatching 内使用的场景，
 * 直接返回已赋值实例（和 ksuApp 等价，避免上层大改）。
 */
fun ksuAppOrThrow(): KernelSUApplication = ksuApp

class KernelSUApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        runCatching { getSystemService(UserManager::class.java)?.isUserUnlocked == true }.getOrDefault(false)

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

    /**
     * 最早入口：Android 系统第一个调用到的 Application 方法。
     * 这里做三件事：
     *  1) 立刻把 this 赋给 _ksuApp（避免 lateinit 未初始化崩）
     *  2) 立刻装 EarlyCrashHandler（只写崩溃日志到文件，不启动 Activity，不依赖任何初始化）
     *  3) 读上次崩溃日志 → 如果有 → 记录下来给 MainActivity / CrashActivity 展示
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        _ksuApp = this

        // 0. 【第一优先】Hidden API 豁免（在系统创建 ContentProvider 之前！）
        // ShizukuProvider.onCreate 时会反射调用一些 @hide 系统 API，
        // 如果在此之前不调用 HiddenApiBypass 加豁免，在 Android 9+ 上
        // 会触发 dalvik 的 hidden-api 黑名单 policy → 直接 SIGABRT（非 Java
        // Throwable，try-catch 也接不住）。这就是"点图标直接闪退没提示"的根因。
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
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
        EarlyCrashHandler.markStage("attachBaseContext_hiddenApiBypassDone")

        // 最早的异常捕获：只能做极简写文件操作，不可启动 Activity / 访问其他 SDK
        runCatching { EarlyCrashHandler.install(base ?: this) }
        EarlyCrashHandler.markStage("attachBaseContext", "pkg=${packageName}")
    }

    override fun onCreate() {
        EarlyCrashHandler.markStage("onCreate_start")
        // 全函数 try-catch：任何业务初始化异常都不会直接杀进程，
        // 而是记录下来，等 MainActivity 启动后弹崩溃页。
        try {
            super.onCreate()
            EarlyCrashHandler.markStage("onCreate_superCalled")

            // 如果是 :error_report / :webui 等子进程，到此为止，跳过所有业务初始化
            if (!isMainProcess()) {
                EarlyCrashHandler.markStage("onCreate_skipSubprocess", currentProcessName)
                return
            }
            EarlyCrashHandler.markStage("onCreate_mainProcess")

            // 主进程：装上完整版异常处理器（会启动 CrashActivity）
            runCatching { GlobalCrashHandler.init() }
            EarlyCrashHandler.markStage("onCreate_GlobalCrashHandlerReady")

            // 洛茜工具箱：主进程启动即安装 Shizuku Binder/授权监听（不依赖 ShizukuProvider）
            runCatching {
                me.weishu.kernelsu.ui.util.PermissionManager.installListenersIfNeeded()
            }
            EarlyCrashHandler.markStage("onCreate_PermissionListenersReady")

            // 读上次崩溃 / 启动阶段日志 —— 由 MainActivity 首帧统一展示
            runCatching { EarlyCrashHandler.handlePendingCrash() }

            if (!isUserUnlocked()) {
                EarlyCrashHandler.markStage("onCreate_userLocked")
                return
            }
            EarlyCrashHandler.markStage("onCreate_userUnlocked")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    val enable = SettingsRepositoryImpl().enablePredictiveBack
                    HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                    setEnableOnBackInvokedCallback(applicationInfo, enable)
                }
                EarlyCrashHandler.markStage("onCreate_hiddenApiDone")
            }

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
            // 任何没被 runCatching 兜住的异常 → 写日志 + 交给 Early/Global 处理器
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
            // 注意：此处不重抛，不杀进程 —— 让系统继续走完 Application.onCreate
            // MainActivity 启动时会读到 pending 崩溃日志自动弹崩溃页。
        }
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
