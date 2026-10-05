package me.weishu.kernelsu.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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

    val cloudState = remember { mutableStateOf(CloudUpdateManager.CloudData()) }
    val localVersionState = remember { mutableStateOf<Int?>(null) }
    val updateAvailableState = remember { mutableStateOf(false) }
    val busyState = remember { mutableStateOf(false) }
    val terminalState = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        localVersionState.value = FileManagerUtils.listSafeFormatVersions().firstOrNull()
        cloudState.value = withContext(Dispatchers.IO) { CloudUpdateManager.fetchCloudData() }
    }

    val toast: (String) -> Unit = { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    val refreshLocal: suspend () -> Unit = {
        localVersionState.value = FileManagerUtils.listSafeFormatVersions().firstOrNull()
    }

    val doDownload: () -> Unit = {
        if (!busyState.value) {
            val url = cloudState.value.safeFormatUrl
            val ver = cloudState.value.safeFormatVersion
            if (url.isBlank() || ver <= 0) {
                toast("未获取到安全格机下载信息")
            } else {
                scope.launch {
                    busyState.value = true
                    val f = SafeFormatManager.downloadToPrivateDir(url, ver)
                    busyState.value = false
                    if (f != null) {
                        updateAvailableState.value = false
                        refreshLocal()
                        toast("下载完成：${CloudUpdateManager.formatInternalVersion(ver)}")
                    } else {
                        toast("下载失败")
                    }
                }
            }
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
                                text = localVersionState.value?.let { CloudUpdateManager.formatInternalVersion(it) } ?: "未下载",
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
                                        busyState.value -> "处理中…"
                                        localVersionState.value == null -> "下载"
                                        updateAvailableState.value -> "更新"
                                        else -> "检查更新"
                                    },
                                    enabled = !busyState.value,
                                    onClick = {
                                        if (busyState.value) return@TextButton
                                        if (localVersionState.value == null) {
                                            doDownload()
                                        } else if (!updateAvailableState.value) {
                                            val local = localVersionState.value ?: 0
                                            if (cloudState.value.safeFormatVersion > local) {
                                                updateAvailableState.value = true
                                                toast("发现新版本")
                                            } else {
                                                toast("已是最新版本")
                                            }
                                        } else {
                                            doDownload()
                                        }
                                    },
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
                                text = localVersionState.value?.let { "${FileManagerUtils.SAFE_FORMAT_DIR.absolutePath}/$it.sh" }
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
                                    text = "停止",
                                    enabled = busyState.value,
                                    onClick = {
                                        val ver = localVersionState.value ?: 0
                                        scope.launch {
                                            val out = FileManagerUtils.stopSafeFormatScript(ver)
                                            terminalState.value = "已发送停止指令\n" + (out ?: "")
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    text = "执行",
                                    enabled = !busyState.value && localVersionState.value != null,
                                    onClick = {
                                        val ver = localVersionState.value
                                        if (ver == null) {
                                            toast("请先下载安全格机文件")
                                        } else if (!busyState.value) {
                                            scope.launch {
                                                busyState.value = true
                                                terminalState.value = "正在执行…"
                                                val out = FileManagerUtils.runSafeFormatScript(ver) { }
                                                busyState.value = false
                                                terminalState.value = out ?: "执行失败：无权限或脚本不存在"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }

                    // 终端输出卡片
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                            Text(
                                text = "终端输出",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            val termScroll = rememberScrollState()
                            LaunchedEffect(terminalState.value) {
                                termScroll.animateScrollTo(termScroll.maxValue)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f))
                                    .verticalScroll(termScroll)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = terminalState.value.ifEmpty { "—" },
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace
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
