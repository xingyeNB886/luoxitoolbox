package me.weishu.kernelsu.ui.crash

import rikka.shizuku.ShizukuProvider

/**
 * 包裹 ShizukuProvider，防止其 onCreate 在 Application.attachBaseContext 之前
 * 抛出异常导致进程被杀（此时 EarlyCrashHandler 还未安装，无法生成崩溃日志）。
 *
 * ShizukuProvider 是 ContentProvider，初始化时机：
 *   Application.attachBaseContext() → ContentProvider.onCreate() → Application.onCreate()
 * 也就是说它在 EarlyCrashHandler 安装之后、GlobalCrashHandler 之前执行，
 * 一旦抛出异常，只会有 early_pending_crash.log（纯日志落盘），下次启动会弹崩溃页。
 */
class SafeShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        return runCatching {
            super.onCreate()
        }.getOrDefault(false)
    }
}
