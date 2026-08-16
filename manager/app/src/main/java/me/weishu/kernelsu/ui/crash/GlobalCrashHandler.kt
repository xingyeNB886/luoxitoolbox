package me.weishu.kernelsu.ui.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import me.weishu.kernelsu.ksuApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 洛茜工具箱全局异常处理器（完整版）。
 *
 * 只在主进程 Application.onCreate 中安装。更早发生的异常已由 EarlyCrashHandler
 * 先写文件，这里负责在异常发生时：
 *   1) 写完整日志
 *   2) 启动独立进程的 NativeCrashActivity（纯原生 View，不依赖 Compose/Miuix，
 *      避免 Compose/Miuix 本身崩导致连崩溃页都看不到）
 *   3) 延迟 800ms 后杀当前进程
 *
 * 注意：此处理器使用 NativeCrashActivity 而不是 CrashActivity（Compose 版），
 * 因为后者在 Compose / MiuixTheme 初始化失败时，本身也会崩。
 */
object GlobalCrashHandler : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    const val CRASH_LOG_FILE_NAME = "latest_crash.log"
    private const val CRASH_DIR = "crash_logs"

    fun init() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /** 把 EarlyCrashHandler 抓到的日志也汇入 latest_crash.log */
    fun importEarlyLog(content: String) {
        runCatching {
            val app = appContext()
            val dir = File(app.filesDir, CRASH_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(app.filesDir, CRASH_LOG_FILE_NAME).writeText(content)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(dir, "imported_early_$stamp.log").writeText(content)
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val app = appContext()

            val stacktrace = StringWriter().use { sw ->
                PrintWriter(sw).use { pw ->
                    pw.println("=== 洛茜工具箱崩溃信息 ===")
                    pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    pw.println("线程: ${t.name} (priority=${t.priority} id=${t.id})")
                    pw.println("进程 pid: ${Process.myPid()}")
                    runCatching {
                        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
                        @Suppress("DEPRECATION")
                        val verCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
                        pw.println("版本: ${pkg.versionName} ($verCode)")
                    }
                    runCatching {
                        pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        pw.println("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    }
                    pw.println()
                    pw.println("=== 堆栈 ===")
                    e.printStackTrace(pw)
                    var cause = e.cause
                    while (cause != null) {
                        pw.println("=== Caused by: ${cause.javaClass.name}: ${cause.message} ===")
                        cause.printStackTrace(pw)
                        cause = cause.cause
                    }
                    sw.toString()
                }
            }

            writeCrashLog(app, stacktrace)

            // 启动 **原生 View 版** 崩溃 Activity（无 Compose / Miuix 依赖）
            runCatching {
                val intent = Intent(app, NativeCrashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(NativeCrashActivity.EXTRA_STACKTRACE, stacktrace)
                    putExtra(NativeCrashActivity.EXTRA_FROM_HANDLER, true)
                }
                app.startActivity(intent)
            }

            try { Thread.sleep(800) } catch (_: Throwable) {}
            Process.killProcess(Process.myPid())
            System.exit(2)
        } catch (t: Throwable) {
            // 处理器本身也崩了；交给系统默认处理，但尽量先落盘
            runCatching {
                val app = appContext()
                writeCrashLog(app, "GlobalCrashHandler 内部异常:\n${t.stackTraceToString()}\n\n原始异常:\n${t.stackTraceToString()}")
            }
            defaultHandler?.uncaughtException(t, t)
        }
    }

    private fun writeCrashLog(ctx: Context, stacktrace: String) {
        runCatching {
            val logDir = File(ctx.filesDir, CRASH_DIR)
            if (!logDir.exists()) logDir.mkdirs()
            File(ctx.filesDir, CRASH_LOG_FILE_NAME).writeText(stacktrace)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(logDir, "crash_${stamp}.log").writeText(stacktrace)
        }
    }

    fun readLatestCrashLog(): String? {
        return runCatching {
            val f = File(appContext().filesDir, CRASH_LOG_FILE_NAME)
            if (f.exists()) f.readText() else null
        }.getOrNull()
    }

    /** 获取非空 Application Context，即使 ksuApp 没 ready 也兜底 */
    private fun appContext(): Context {
        return runCatching { ksuApp }.getOrNull()
            ?: throw IllegalStateException("No application context available in GlobalCrashHandler")
    }
}
