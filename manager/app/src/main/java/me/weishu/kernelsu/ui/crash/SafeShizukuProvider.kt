package me.weishu.kernelsu.ui.crash

import rikka.shizuku.ShizukuProvider

/**
 * 包裹 ShizukuProvider，做两层防御：
 *   1. 【外层】KernelSUApplication.attachBaseContext 先调 HiddenApiBypass，
 *      在系统创建 Provider 之前就把所有 hidden API 豁免，避免 native SIGABRT。
 *   2. 【内层】Java 层 onCreate 整体 runCatching，即使 SDK 抛异常也不至于杀进程。
 *
 * 这个 Provider 是 Shizuku SDK requestPermission() / checkSelfPermission()
 * 正常工作的必需组件（官方 demo 也声明它）。
 */
class SafeShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        EarlyCrashHandler.markStage("SafeShizukuProvider_onCreate_start")
        val result = runCatching { super.onCreate() }.getOrDefault(false)
        EarlyCrashHandler.markStage("SafeShizukuProvider_onCreate_end", "result=$result")
        return result
    }
}
