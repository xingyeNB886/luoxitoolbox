package me.weishu.kernelsu.ui.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 洛茜工具箱全局异常处理器
 * 捕获未处理异常，写入崩溃日志并启动 CrashActivity 展示。
 */
object GlobalCrashHandler : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /** 最近一次崩溃日志文件 */
    const val CRASH_LOG_FILE_NAME = "latest_crash.log"

    fun init() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val stacktrace = StringWriter().use { sw ->
                PrintWriter(sw).use { pw ->
                    pw.println("=== 洛茜工具箱崩溃信息 ===")
                    pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    pw.println("线程: ${t.name} (priority=${t.priority} id=${t.id})")
                    pw.println("进程 pid: ${Process.myPid()}")
                    try {
                        val pkg = ksuApp.packageManager.getPackageInfo(ksuApp.packageName, 0)
                        pw.println("版本: ${pkg.versionName} (${pkg.longVersionCode})")
                    } catch (_: Throwable) {}
                    try {
                        pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        pw.println("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    } catch (_: Throwable) {}
                    pw.println()
                    pw.println("=== 堆栈 ===")
                    e.printStackTrace(pw)
                    pw.println()
                    var cause = e.cause
                    while (cause != null) {
                        pw.println("=== Caused by: ${cause.javaClass.name}: ${cause.message} ===")
                        cause.printStackTrace(pw)
                        cause = cause.cause
                    }
                    sw.toString()
                }
            }

            // 写入崩溃日志
            @Suppress("DeferredResultUnused")
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { writeCrashLog(stacktrace) }
            }

            // 启动 CrashActivity 展示给用户（必须在主线程）
            val ctx: Context = ksuApp
            val intent = Intent(ctx, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(CrashActivity.EXTRA_STACKTRACE, stacktrace)
                putExtra(CrashActivity.EXTRA_FROM_HANDLER, true)
            }
            runCatching {
                ctx.startActivity(intent)
            }

            // 等一小会儿让 CrashActivity 有机会启动
            try {
                Thread.sleep(800)
            } catch (_: Throwable) {}

            // 杀死自己
            Process.killProcess(Process.myPid())
            System.exit(2)
        } catch (t: Throwable) {
            // 处理器本身出错，交给系统默认处理器
            defaultHandler?.uncaughtException(Thread.currentThread(), t)
        }
    }

    private suspend fun writeCrashLog(stacktrace: String) = withContext(Dispatchers.IO) {
        runCatching {
            val logDir = File(ksuApp.filesDir, "crash_logs")
            if (!logDir.exists()) logDir.mkdirs()
            // latest
            val latest = File(ksuApp.filesDir, CRASH_LOG_FILE_NAME)
            latest.writeText(stacktrace)
            // 再存一份带时间戳的备份
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(logDir, "crash_${stamp}.log").writeText(stacktrace)
        }
    }

    /** 读取最近一次崩溃日志（CrashActivity 可调用） */
    fun readLatestCrashLog(): String? {
        return runCatching {
            val f = File(ksuApp.filesDir, CRASH_LOG_FILE_NAME)
            if (f.exists()) f.readText() else null
        }.getOrNull()
    }
}
