package me.weishu.kernelsu.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.BackgroundUtils
import me.weishu.kernelsu.ui.util.CloudUpdateManager
import me.weishu.kernelsu.ui.util.NetUtils
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private sealed interface VerifyState {
    data object Loading : VerifyState
    data class Latest(val cloud: CloudUpdateManager.CloudData) : VerifyState
    data class NeedUpdate(val cloud: CloudUpdateManager.CloudData) : VerifyState
    data class SignatureInvalid(val cloud: CloudUpdateManager.CloudData) : VerifyState
    data object Failure : VerifyState
}

/** 内部版本号（如 1000000）转显示版本号（如 1.0.0） */
private fun formatInternalVersion(v: Int): String = "${v / 1000000}.${(v % 1000000) / 1000}.${v % 1000}"

/**
 * 开屏校验（启动时检查更新）：检测 QQ 收藏里的最新版本 + 应用签名；
 * 有新版弹「新版本」对话框（更新日志 + 下载安装），已最新自动进入，
 * 签名异常提示安装官方版。页面显示背景图。
 */
@Composable
fun StartupVerifyScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<VerifyState>(VerifyState.Loading) }
    var generation by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val dialogShow = remember { mutableStateOf(true) }

    LaunchedEffect(generation) {
        state = VerifyState.Loading
        dialogShow.value = true
        state = withContext(Dispatchers.IO) {
            // 签名校验（防二次打包）+ 读 QQ 收藏里的版本/更新信息
            val signatureValid = CloudUpdateManager.verifyAppSignature(context)
            val data = CloudUpdateManager.fetchCloudData()
            when {
                !signatureValid -> VerifyState.SignatureInvalid(data)
                data.internalVersion > BuildConfig.VERSION_CODE -> VerifyState.NeedUpdate(data)
                data.internalVersion > 0 -> VerifyState.Latest(data)
                else -> VerifyState.Failure
            }
        }
    }

    // 已是最新 → 短暂提示后自动进入
    LaunchedEffect(state) {
        if (state is VerifyState.Latest) {
            delay(700)
            onContinue()
        }
    }

    val bg = remember { BackgroundUtils.loadBitmap(context) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun installApk(file: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            context.startActivity(intent)
        }.onFailure { toast("安装失败") }
    }

    fun openBrowser(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法打开浏览器") }
    }

    fun downloadAndInstall(url: String) {
        if (url.isBlank()) {
            toast("无可用下载")
            return
        }
        scope.launch {
            downloading = true
            progress = 0
            downloadError = null
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = NetUtils.fetchBytes(url)
                    val f = java.io.File(context.cacheDir, "luoxi_update.apk")
                    java.io.FileOutputStream(f).use { it.write(bytes) }
                    f
                }.getOrNull()
            }
            downloading = false
            if (file != null && file.length() > 0L) {
                progress = 100
                dialogShow.value = false
                installApk(file)
            } else {
                downloadError = "下载失败"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bg != null) {
            Image(
                bitmap = bg.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        } else {
            Box(modifier = Modifier.fillMaxSize().background(colorScheme.surface))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "启动校验",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(18.dp))

            when (val s = state) {
                VerifyState.Loading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(10.dp))
                            Text("正在检查更新…", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                        }
                    }
                }

                VerifyState.Failure -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("检查更新失败", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text("网络不可用或服务器返回异常，请检查网络后重试。", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(text = "退出", modifier = Modifier.weight(1f), onClick = {
                            (context as? android.app.Activity)?.finishAffinity()
                        })
                        TextButton(
                            text = "重试",
                            modifier = Modifier.weight(1f),
                            onClick = { generation++ },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }

                else -> Unit
            }
        }

        // 新版本对话框（照月虹：标题「新版本 x」+ 更新日志 + 下载进度 + 取消/安装）
        val needUpdateCloud = (state as? VerifyState.NeedUpdate)?.cloud
        if (needUpdateCloud != null) {
            SuperDialog(
                show = dialogShow,
                title = "新版本 ${formatInternalVersion(needUpdateCloud.internalVersion)}",
                onDismissRequest = { if (!downloading) { dialogShow.value = false; onContinue() } },
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val body = needUpdateCloud.versionHistory.ifBlank { needUpdateCloud.announcement }
                        if (body.isNotBlank()) {
                            Text(
                                text = body,
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        if (downloading) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("下载中… $progress%", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        downloadError?.let { err ->
                            Spacer(Modifier.height(12.dp))
                            Text("下载失败：$err", fontSize = 13.sp, color = colorScheme.error)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "取消",
                                modifier = Modifier.weight(1f),
                                enabled = !downloading,
                                onClick = { dialogShow.value = false; onContinue() }
                            )
                            TextButton(
                                text = if (downloadError != null) "去下载" else "安装",
                                modifier = Modifier.weight(1f),
                                enabled = !downloading && needUpdateCloud.downloadUrl.isNotBlank(),
                                onClick = {
                                    if (downloadError != null) {
                                        dialogShow.value = false
                                        openBrowser(needUpdateCloud.downloadUrl)
                                    } else {
                                        downloadAndInstall(needUpdateCloud.downloadUrl)
                                    }
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }
            )
        }

        // 签名异常对话框
        val sigCloud = (state as? VerifyState.SignatureInvalid)?.cloud
        if (sigCloud != null) {
            SuperDialog(
                show = dialogShow,
                title = "应用签名异常",
                onDismissRequest = { /* 不可关闭 */ },
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "检测到非官方签名（可能被二次打包），为保障安全请安装官方版本后使用。",
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary
                        )
                        if (downloading) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("下载中… $progress%", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "退出",
                                modifier = Modifier.weight(1f),
                                enabled = !downloading,
                                onClick = { (context as? android.app.Activity)?.finishAffinity() }
                            )
                            TextButton(
                                text = "安装官方版",
                                modifier = Modifier.weight(1f),
                                enabled = !downloading,
                                onClick = { downloadAndInstall(sigCloud.downloadUrl) },
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }
            )
        }
    }
}
