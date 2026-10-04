package me.weishu.kernelsu.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.util.CloudUpdateManager
import me.weishu.kernelsu.ui.util.FileManagerUtils
import me.weishu.kernelsu.ui.util.SafeFormatManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 安全格机页（底部第 3 项）：版本检查/更新/下载（脚本存应用私有目录）、安全格机文件目录、执行文件。
 */
@Composable
fun SafeFormatScreen(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    var cloud by remember { mutableStateOf(CloudUpdateManager.CloudData()) }
    var localVersion by remember { mutableStateOf<Int?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshLocal() {
        localVersion = FileManagerUtils.listSafeFormatVersions().firstOrNull()
    }

    LaunchedEffect(Unit) {
        refreshLocal()
        cloud = withContext(Dispatchers.IO) { CloudUpdateManager.fetchCloudData() }
    }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun doDownload() {
        if (busy) return
        val url = cloud.safeFormatUrl
        val ver = cloud.safeFormatVersion
        if (url.isBlank() || ver <= 0) {
            toast("未获取到安全格机下载信息")
            return
        }
        scope.launch {
            busy = true
            val f = SafeFormatManager.downloadToPrivateDir(url, ver)
            busy = false
            if (f != null) {
                updateAvailable = false
                refreshLocal()
                toast("下载完成：${CloudUpdateManager.formatInternalVersion(ver)}")
            } else {
                toast("下载失败")
            }
        }
    }

    fun onMainButton() {
        if (busy) return
        if (localVersion == null) {
            doDownload()
        } else if (!updateAvailable) {
            val local = localVersion ?: 0
            if (cloud.safeFormatVersion > local) {
                updateAvailable = true
                toast("发现新版本")
            } else {
                toast("已是最新版本")
            }
        } else {
            doDownload()
        }
    }

    fun onExecute() {
        val ver = localVersion
        if (ver == null) {
            toast("请先下载安全格机文件")
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            val ok = FileManagerUtils.runSafeFormatScript(ver) { }
            busy = false
            toast(if (ok) "执行完成" else "执行失败，请检查 Root/ADB 权限")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                },
                color = Color.Transparent,
                title = stringResource(R.string.safe_format),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .hazeSource(state = hazeState)
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 版本卡片
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(
                                text = "版本",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = localVersion?.let { CloudUpdateManager.formatInternalVersion(it) } ?: "未下载",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    text = when {
                                        busy -> "处理中…"
                                        localVersion == null -> "下载"
                                        updateAvailable -> "更新"
                                        else -> "检查更新"
                                    },
                                    enabled = !busy,
                                    onClick = { onMainButton() },
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }

                    // 安全格机文件目录卡片（无按钮）
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(
                                text = "安全格机文件目录",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = localVersion?.let { "${FileManagerUtils.SAFE_FORMAT_DIR.absolutePath}/$it.sh" }
                                    ?: FileManagerUtils.SAFE_FORMAT_DIR.absolutePath,
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }

                    // 执行文件卡片
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(
                                text = "执行文件",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "需要授予 Root 或 ADB 权限后执行",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    text = "执行",
                                    enabled = !busy,
                                    onClick = { onExecute() },
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(bottomInnerPadding))
            }
        }
    }
}
