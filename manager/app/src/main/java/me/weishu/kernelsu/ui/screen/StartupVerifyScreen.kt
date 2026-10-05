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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.BackgroundUtils
import me.weishu.kernelsu.ui.util.CloudUpdateManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private sealed interface VerifyState {
    data object Loading : VerifyState
    data class Success(val cloud: CloudUpdateManager.CloudData) : VerifyState
    data object Failure : VerifyState
}

/** 内部版本号（如 1000000）转显示版本号（如 1.0.0） */
private fun formatInternalVersion(v: Int): String = "${v / 1000000}.${(v % 1000000) / 1000}.${v % 1000}"

/**
 * 启动验证页：每次进入应用先显示，联网读取 QQ 收藏的版本/作者/公告；
 * 版本过低提示更新，无法联网则提示失败（可重试/退出）。页面显示背景图。
 */
@Composable
fun StartupVerifyScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<VerifyState>(VerifyState.Loading) }
    var generation by remember { mutableIntStateOf(0) }

    LaunchedEffect(generation) {
        state = VerifyState.Loading
        state = withContext(Dispatchers.IO) {
            val data = CloudUpdateManager.fetchCloudData()
            if (data.internalVersion > 0) VerifyState.Success(data) else VerifyState.Failure
        }
    }

    val bg = remember { BackgroundUtils.loadBitmap(context) }

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
                text = "开屏校验",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(18.dp))

            when (val s = state) {
                VerifyState.Loading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("正在校验…", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Text("正在获取服务器信息，请稍候", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }

                is VerifyState.Success -> {
                    val localCode = BuildConfig.VERSION_CODE
                    val needUpdate = s.cloud.internalVersion > localCode
                    val version = formatInternalVersion(s.cloud.internalVersion)
                    val author = s.cloud.author.ifBlank { "—" }
                    val announcement = s.cloud.announcement.ifBlank { "暂无公告" }

                    Card(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (needUpdate) {
                                Text(
                                    "发现新版本",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.error
                                )
                                Text(
                                    "当前 ${BuildConfig.VERSION_NAME}，最新 $version，请更新后使用",
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurfaceVariantSummary
                                )
                            } else {
                                Text(
                                    "公告",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoTile("版本", version, Modifier.weight(1f))
                                InfoTile("作者", author, Modifier.weight(1f))
                            }
                            Text("公告内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.06f))
                            ) {
                                Text(
                                    text = if (needUpdate) announcement else announcement,
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    if (needUpdate) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(text = "退出", modifier = Modifier.weight(1f), onClick = {
                                (context as? android.app.Activity)?.finishAffinity()
                            })
                            TextButton(text = "继续", modifier = Modifier.weight(1f), onClick = onContinue)
                            TextButton(
                                text = "立即更新",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (s.cloud.downloadUrl.isBlank()) {
                                        android.widget.Toast.makeText(context, "更新链接缺失", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(s.cloud.downloadUrl)
                                        )
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    } else {
                        TextButton(
                            text = "继续",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onContinue,
                            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }

                VerifyState.Failure -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("无法完成启动验证", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colorScheme.error)
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
                            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface, maxLines = 2)
        }
    }
}
