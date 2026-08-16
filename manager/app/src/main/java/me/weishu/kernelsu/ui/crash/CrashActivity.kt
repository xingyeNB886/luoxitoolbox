package me.weishu.kernelsu.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.theme.KernelSUTheme

/**
 * 崩溃展示页
 * Intent 要求: EXTRA_STACKTRACE（字符串）
 * 也会自动读取 GlobalCrashHandler 的最新日志（用于应用冷启动后主动查看）
 */
class CrashActivity : ComponentActivity() {

    companion object {
        const val EXTRA_STACKTRACE = "extra_stacktrace"
        const val EXTRA_FROM_HANDLER = "extra_from_handler"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawSt = intent.getStringExtra(EXTRA_STACKTRACE)
            ?: GlobalCrashHandler.readLatestCrashLog()
            ?: "(无法读取崩溃信息)"

        setContent {
            KernelSUTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("洛茜工具箱 · 崩溃报告") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        )
                    },
                ) { innerPadding ->
                    CrashScreen(
                        padding = innerPadding,
                        stacktrace = rawSt,
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashScreen(
    padding: PaddingValues,
    stacktrace: String,
) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "应用发生异常，已收集以下错误信息。",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "请点底部「复制堆栈」按钮发给开发者定位问题。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 堆栈
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            Text(
                text = stacktrace,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { copyStacktrace(ctx, stacktrace) },
                modifier = Modifier.weight(1f),
            ) { Text("复制堆栈") }
            FilledTonalButton(
                onClick = { (ctx as? ComponentActivity)?.finishAffinity() },
                modifier = Modifier.weight(1f),
            ) { Text("关闭应用") }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage(ctx.packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ctx.startActivity(intent)
                }
                (ctx as? ComponentActivity)?.finish()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重启应用") }
    }
}

private fun copyStacktrace(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("luoxi-crash", text))
    }
}
