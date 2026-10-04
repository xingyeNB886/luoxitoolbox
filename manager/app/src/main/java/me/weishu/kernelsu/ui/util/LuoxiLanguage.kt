package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 洛茜工具箱：全局语言切换管理器。
 *
 * 语言模式存于 prefs "language_mode"：
 *  - 0 = 简体中文（zh-CN）
 *  - 1 = 狐娘语（fox）
 *  - 2 = 猫娘语（cat）
 *
 * 狐娘语/猫娘语用自定义语言代码（fox/cat），
 * 对应资源文件夹 values-fox / values-cat 的 strings.xml。
 * 切换后通过 recreate() 立即生效；退出不丢失（持久化于 prefs）。
 */
object LuoxiLanguage {

    const val MODE_ZH_CN = 0
    const val MODE_FOX = 1
    const val MODE_CAT = 2

    fun getCurrentMode(context: Context): Int {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getInt("language_mode", MODE_ZH_CN)
    }

    fun setCurrentMode(context: Context, mode: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putInt("language_mode", mode).apply()
    }

    /**
     * 根据当前语言模式返回对应 Locale。
     * 简中 → zh-CN；狐娘 → fox；猫娘 → cat。
     */
    fun getLocale(context: Context): Locale {
        return when (getCurrentMode(context)) {
            MODE_FOX -> Locale("fox")
            MODE_CAT -> Locale("cat")
            else -> Locale("zh", "CN")
        }
    }

    /**
     * 用当前语言 Locale 包装 Context（供 attachBaseContext / createConfigurationContext 使用）。
     */
    fun wrapContext(context: Context): Context {
        val locale = getLocale(context)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        //noinspection DEPRECATED: 旧 API 仍是最可靠的运行时 locale 设置方式
        return context.createConfigurationContext(config)
    }
}
