package me.weishu.kernelsu.ui.crash

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极早期异常处理器。
 * 在 Application.attachBaseContext 时就安装，此时：
 *   - ksuApp 刚刚赋值，其他组件（OKHttp / ViewModel / Compose / Miuix / Natives）均未初始化
 *   - 不能启动 Activity（可能没有权限 / 组件还没准备好）
 *   - 不能引用任何可能触发类加载 & 静态初始化的对象
 *
 * 唯一职责：把 Throwable 写成文件，下次启动时再由 GlobalCrashHandler /
 * NativeCrashActivity 展示给用户。
 */
object EarlyCrashHandler : Thread.UncaughtExceptionHandler {

    private const val PENDING_FILE = "early_pending_crash.log"
    private const val EARLY_DIR = "crash_logs"

    @Volatile
    private var earlyContext: Context? = null

    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 是否已经有未读崩溃（下次启动 MainActivity 时会读到） */
    @Volatile
    var hasPendingCrash: Boolean = false
        private set

    fun install(context: Context) {
        this.earlyContext = context.applicationContext ?: context
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        // 安装时顺便检查：是否有上次遗留的崩溃文件
        hasPendingCrash = runCatching { getPendingFile().exists() }.getOrDefault(false)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            writeToFile(t, e)
        } catch (_: Throwable) {
            // 写文件都失败，什么都做不了
        }

        // 最终交给系统默认处理器（无论如何默认行为是杀进程；但是我们先把日志落盘了）
        try {
            defaultHandler?.uncaughtException(t, e)
        } catch (_: Throwable) {
            // 默认处理器也崩，强杀
            try {
                Process.killProcess(Process.myPid())
                System.exit(2)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 下次启动主进程时调用：如果上次崩了，把早期日志转存到 GlobalCrashHandler
     * 统一路径，并返回日志内容（供 UI 展示）。
     */
    fun handlePendingCrash(): String? {
        val ctx = earlyContext ?: return null
        val pending = getPendingFile(ctx)
        if (!pending.exists()) return null
        return runCatching {
            val content = pending.readText()
            // 转存到 GlobalCrashHandler 路径
            runCatching { GlobalCrashHandler.importEarlyLog(content) }
            // 删 pending，下次不重复弹
            pending.delete()
            hasPendingCrash = false
            content
        }.getOrNull()
    }

    fun readPending(): String? {
        val ctx = earlyContext ?: return null
        val f = getPendingFile(ctx)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    // --- 内部工具 ---

    private fun getPendingFile(ctx: Context? = earlyContext): File {
        val c = ctx ?: throw IllegalStateException("EarlyCrashHandler.context not set")
        return File(c.filesDir, PENDING_FILE)
    }

    private fun writeToFile(t: Thread, e: Throwable) {
        val ctx = earlyContext ?: return
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== 洛茜工具箱 · 极早期崩溃 (EarlyCrashHandler) ===")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            pw.println("线程: ${t.name} (priority=${t.priority} id=${t.id})")
            pw.println("进程 pid: ${Process.myPid()}")
            runCatching {
                val pm = ctx.packageManager
                val pkg = pm.getPackageInfo(ctx.packageName, 0)
                pw.println("版本: ${pkg.versionName}")
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
        }
        val content = sw.toString()

        // 1) 写 pending 文件（下次启动一定会展示）
        runCatching { getPendingFile(ctx).writeText(content) }

        // 2) 同时存一份备份到 crash_logs 目录
        runCatching {
            val dir = File(ctx.filesDir, EARLY_DIR)
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(dir, "early_$stamp.log").writeText(content)
        }
    }
}
