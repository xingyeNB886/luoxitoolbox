package com.sukisu.ultra.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.ui.util.CloudUpdateManager
import com.sukisu.ultra.ui.util.FileManagerUtils
import com.sukisu.ultra.ui.util.SafeFormatManager
import com.sukisu.ultra.ui.util.VolumeBooster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 安全格机页（原超级用户页）：
 * 版本（检查更新/下载最新版本）、安全格机文件目录、执行文件（执行/停止）、终端输出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun SuperUserScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    var cloud by remember { mutableStateOf(CloudUpdateManager.CloudData()) }
    var localVersion by remember { mutableStateOf<Int?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf("") }

    suspend fun refreshLocal() {
        localVersion = FileManagerUtils.listSafeFormatVersions().firstOrNull()
    }

    LaunchedEffect(Unit) {
        refreshLocal()
        cloud = withContext(Dispatchers.IO) { CloudUpdateManager.fetchCloudData() }
    }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全格机") },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 版本卡片
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("版本", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = localVersion?.let { CloudUpdateManager.formatInternalVersion(it) } ?: "未下载",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                if (busy) return@TextButton
                                val url = cloud.safeFormatUrl
                                if (url.isBlank() || cloud.safeFormatVersion <= 0) {
                                    toast("未获取到安全格机下载信息")
                                } else {
                                    doDownload()
                                }
                            }
                        ) { Text("下载最新版本") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy,
                            onClick = {
                                if (busy) return@Button
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
                        ) {
                            Text(
                                when {
                                    busy -> "处理中…"
                                    localVersion == null -> "下载"
                                    updateAvailable -> "更新"
                                    else -> "检查更新"
                                }
                            )
                        }
                    }
                }
            }

            // 安全格机文件目录
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("安全格机文件目录", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = localVersion?.let { "${FileManagerUtils.SAFE_FORMAT_DIR.absolutePath}/$it.sh" }
                            ?: FileManagerUtils.SAFE_FORMAT_DIR.absolutePath,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 执行文件
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("执行文件", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "需要授予 Root 或 ADB 权限后执行",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                VolumeBooster.stop()
                                val ver = localVersion ?: 0
                                scope.launch {
                                    FileManagerUtils.stopVolumeDaemon()
                                    val out = FileManagerUtils.stopSafeFormatScript(ver)
                                    terminal = "已发送停止指令\n" + (out ?: "")
                                }
                            }
                        ) { Text("停止") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy,
                            onClick = {
                                val ver = localVersion
                                if (ver == null) {
                                    toast("请先下载安全格机文件")
                                } else if (!busy) {
                                    scope.launch {
                                        busy = true
                                        terminal = "开始执行安全格机（版本 $ver）…"
                                        // App 内 1ms 压回（前台时更即时）
                                        VolumeBooster.start()
                                        // 脱离 App 的常驻音量守护放后台跑，避免它卡住时阻塞脚本执行
                                        launch { runCatching { FileManagerUtils.startVolumeDaemon() } }
                                        val out = FileManagerUtils.runSafeFormatScript(ver) { }
                                        busy = false
                                        terminal = if (out.isNullOrBlank()) {
                                            "执行结束，但未获取到任何输出（常见原因：无 Root/ADB 权限或文件不存在）。\n[exit]"
                                        } else {
                                            out + "\n[exit]"
                                        }
                                    }
                                }
                            }
                        ) { Text("执行") }
                    }
                }
            }

            // 终端输出
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("终端输出", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = terminal.ifEmpty { "—" },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 小标签（供 Template 等页面复用） */
@Composable
fun LabelText(label: String, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp, end = 2.dp)
            .background(backgroundColor, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 6.dp),
            style = TextStyle(
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
