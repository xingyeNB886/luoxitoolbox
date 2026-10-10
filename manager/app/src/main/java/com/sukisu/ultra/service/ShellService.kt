package com.sukisu.ultra.service

/**
 * Shizuku UserService 实现
 * 运行在 Shizuku 服务进程（adb shell 或 root 身份）中，
 * 用于执行普通应用没有权限执行的 shell 命令（初始化目录等）。
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
