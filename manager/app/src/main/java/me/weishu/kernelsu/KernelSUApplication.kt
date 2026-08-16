package me.weishu.kernelsu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.crash.GlobalCrashHandler
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

lateinit var ksuApp: KernelSUApplication

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
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        // 1. 优先初始化全局异常处理器（最早捕获崩溃）
        runCatching { GlobalCrashHandler.init() }

        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val enable = SettingsRepositoryImpl().enablePredictiveBack
                HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                setEnableOnBackInvokedCallback(applicationInfo, enable)
            }
        }

        // 2. 超级用户页应用列表：延后到 UI 打开对应 tab 再加载，
        //    避免 onCreate 里因 PackageManager / Natives 访问异常崩溃
        runCatching {
            val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
            superUserViewModel.loadAppList()
        }

        runCatching {
            val webroot = File(dataDir, "webroot")
            if (!webroot.exists()) {
                webroot.mkdir()
            }
        }

        runCatching {
            // Provide working env for rust's temp_dir()
            Os.setenv("TMPDIR", cacheDir.absolutePath, true)
        }

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
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
