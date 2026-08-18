package com.sukisu.ultra

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import coil.Coil
import coil.ImageLoader
import com.dergoogler.mmrl.platform.Platform
import me.zhanghai.android.appiconloader.coil.AppIconFetcher
import me.zhanghai.android.appiconloader.coil.AppIconKeyer
import java.io.File
import java.util.Locale

lateinit var ksuApp: KernelSUApplication

class KernelSUApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // 【第一优先】Hidden API 豁免（在系统创建 ContentProvider 之前！）
        // ShizukuProvider.onCreate 会反射调用 @hide 系统 API，若未豁免，
        // Android 9+ 会触发 hidden-api 黑名单 → native SIGABRT（try-catch 接不住）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                    "Lrikka/shizuku/",
                    "Landroid/os/",
                    "Landroid/content/pm/",
                    "Landroid/os/UserHandle;",
                    "L"
                )
            }
        }

        val prefs = base.getSharedPreferences("settings", MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", "") ?: ""

        var context = base
        if (languageCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageCode)
            Locale.setDefault(locale)

            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)

            context = base.createConfigurationContext(config)
        }

        super.attachBaseContext(context)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun getResources(): Resources {
        val resources = super.getResources()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", "") ?: ""

        if (languageCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageCode)
            val config = Configuration(resources.configuration)
            config.setLocale(locale)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return createConfigurationContext(config).resources
            } else {
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
        }

        return resources
    }

    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        Platform.setHiddenApiExemptions()

        // 洛茜工具箱：安装 Shizuku Binder 监听（授权弹窗结果 / Binder 变化 → 推 Flow）
        // ShizukuProvider 已通过 Manifest 声明，SDK 内部自动初始化，无需手动 initialize
        runCatching {
            com.sukisu.ultra.ui.util.PermissionManager.installListenersIfNeeded()
        }

        val context = this
        val iconSize = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(AppIconKeyer())
                    add(AppIconFetcher.Factory(iconSize, false, context))
                }
                .build()
        )

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLanguageSetting()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun applyLanguageSetting() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val languageCode = prefs.getString("app_language", "") ?: ""

        if (languageCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageCode)
            Locale.setDefault(locale)

            val resources = resources
            val config = Configuration(resources.configuration)
            config.setLocale(locale)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createConfigurationContext(config)
            } else {
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
        }
    }
}