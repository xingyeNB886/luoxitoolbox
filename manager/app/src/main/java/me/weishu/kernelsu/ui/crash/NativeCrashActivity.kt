package me.weishu.kernelsu.ui.crash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt
import me.weishu.kernelsu.R

/**
 * **原生 View 版** 崩溃展示页。
 *
 * 绝对不引用 Compose / MiuixTheme / 任何自定义 UI 库。
 * 只用 Android 框架的 View 子类，确保哪怕 Compose/主题/资源本身有问题，
 * 这个 Activity 也能独立渲染出来，让用户看到堆栈。
 */
class NativeCrashActivity : Activity() {

    companion object {
        const val EXTRA_STACKTRACE = "extra_stacktrace"
        const val EXTRA_FROM_HANDLER = "extra_from_handler"
        const val EXTRA_PENDING_EARLY = "extra_pending_early"
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // 沉浸式纯色状态栏（即使主题出问题也不会崩）
        runCatching { setSystemBarDark() }

        // --- 组装 UI：LinearLayout 根 + 标题 + 说明 + ScrollView(TextView) + 按钮行 ---

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }

        val titleTv = TextView(this).apply {
            text = getString(R.string.crash_report_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0xFFB3261E.toInt()) // Material error
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        root.addView(titleTv, -1, -2)

        val tip = TextView(this).apply {
            text = getString(R.string.crash_report_content)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF333333.toInt())
            setPadding(dp(6f), dp(12f), dp(6f), dp(10f))
        }
        root.addView(tip, -1, -2)

        val raw = intent.getStringExtra(EXTRA_STACKTRACE)
            ?: EarlyCrashHandler.readPending()
            ?: GlobalCrashHandler.readLatestCrashLog()
            ?: "(无法读取崩溃信息。可能是 native 信号导致进程直接被杀，或存储空间不可用。)"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFEBEBEB.toInt())
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }
        val stackTv = TextView(this).apply {
            text = raw
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF222222.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            setTextIsSelectable(true)
        }
        scroll.addView(stackTv, -1, -1)

        // ScrollView 撑满中间空间，按钮在底部
        val scrollLp = LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4f)
            bottomMargin = dp(12f)
        }
        root.addView(scroll, scrollLp)

        // 三个按钮：复制堆栈 / 关闭应用 / 重启应用
        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val btnCopy = makeButton("复制堆栈").apply {
            setOnClickListener {
                copyToClipboard(raw)
                Toast.makeText(this@NativeCrashActivity, "堆栈已复制到剪贴板", Toast.LENGTH_LONG).show()
            }
        }
        val btnClose = makeButton("关闭应用").apply {
            setOnClickListener { finishAffinityCompat() }
        }
        btnRow1.addView(btnCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        btnRow1.addView(btnClose, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6f) })
        root.addView(btnRow1, -1, -2)

        val btnRestart = makeButton("重启应用", isPrimary = true).apply {
            setOnClickListener {
                val pm = packageManager
                val launch = pm.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
                finish()
            }
        }
        val lpRestart = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }
        root.addView(btnRestart, lpRestart)

        setContentView(root)
    }

    private fun makeButton(text: String, isPrimary: Boolean = false): Button {
        val ctx = this
        val b = Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            if (isPrimary) {
                setBackgroundColor(0xFFB3261E.toInt())
                setTextColor(Color.WHITE)
            } else {
                setBackgroundColor(0xFFD9D9D9.toInt())
                setTextColor(0xFF111111.toInt())
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        return b
    }

    private fun dp(v: Float): Int {
        val d = resources.displayMetrics.density
        return (v * d).roundToInt()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("luoxi-crash", text))
        }
    }

    private fun finishAffinityCompat() {
        runCatching { finishAffinity() }
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarDark() {
        val w = window ?: return
        w.statusBarColor = 0xFFB3261E.toInt()
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            val decor = w.decorView
            var sysUi = decor.systemUiVisibility
            sysUi = sysUi and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            decor.systemUiVisibility = sysUi
        }
    }
}
