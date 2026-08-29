package com.sukisu.ultra.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.CloudUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启动验证界面（每次进入应用时显示）：
 * 联网读取 QQ 收藏的公告/作者/版本，验证通过后才允许进入主页；
 * 没有联网时显示「无法完成启动验证」，只能重试或退出。
 */
private sealed interface VerifyState {
    data object Loading : VerifyState
    data class Success(val cloud: CloudUpdateManager.CloudData) : VerifyState
    data object Failure : VerifyState
}

@Composable
fun StartupVerifyScreen(onContinue: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    var state by remember { mutableStateOf<VerifyState>(VerifyState.Loading) }
    var refreshGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshGeneration) {
        state = VerifyState.Loading
        state = withContext(Dispatchers.IO) {
            val data = CloudUpdateManager.fetchCloudData()
            if (data.internalVersion > 0) VerifyState.Success(data) else VerifyState.Failure
        }
    }

    val localVersion = BuildConfig.VERSION_NAME
    val localVersionCode = BuildConfig.VERSION_CODE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 品牌头
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.startup_ready_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                when (val s = state) {
                    VerifyState.Loading -> LoadingContent(colors = colors)
                    is VerifyState.Success -> {
                        val needUpdate = s.cloud.internalVersion > localVersionCode
                        val accent = if (needUpdate) colors.error else colors.primary
                        val version = s.cloud.internalVersion.toString()
                        val author = s.cloud.author.ifBlank {
                            stringResource(R.string.startup_author_fallback)
                        }
                        val announcement = s.cloud.announcement.ifBlank {
                            stringResource(R.string.startup_default_text)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (needUpdate) R.string.startup_update_badge
                                        else R.string.startup_announcement_badge
                                    ),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(
                                        if (needUpdate) R.string.startup_update_title
                                        else R.string.startup_announcement_title
                                    ),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (needUpdate) colors.error else colors.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (needUpdate) R.string.startup_update_subtitle
                                        else R.string.startup_announcement_subtitle
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }

                            if (needUpdate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    InfoTile(
                                        label = stringResource(R.string.startup_local_version_label),
                                        value = localVersion,
                                        accent = accent,
                                        modifier = Modifier.weight(1f),
                                    )
                                    InfoTile(
                                        label = stringResource(R.string.startup_version_label),
                                        value = version,
                                        accent = accent,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                InfoTile(
                                    label = stringResource(R.string.startup_author_label),
                                    value = author,
                                    accent = accent,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    InfoTile(
                                        label = stringResource(R.string.startup_version_label),
                                        value = version,
                                        accent = accent,
                                        modifier = Modifier.weight(1f),
                                    )
                                    InfoTile(
                                        label = stringResource(R.string.startup_author_label),
                                        value = author,
                                        accent = accent,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.startup_announcement_label),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = if (needUpdate) {
                                            stringResource(
                                                R.string.startup_update_message,
                                                localVersion,
                                                version,
                                            )
                                        } else {
                                            announcement
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    VerifyState.Failure -> FailureContent(colors = colors)
                }
            }

            Spacer(Modifier.height(14.dp))

            when (val s = state) {
                is VerifyState.Success -> {
                    val needUpdate = s.cloud.internalVersion > localVersionCode
                    if (needUpdate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                onClick = {
                                    (context as? android.app.Activity)?.finishAffinity()
                                },
                            ) {
                                Text(stringResource(R.string.startup_exit), fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = colors.onError,
                                ),
                                onClick = {
                                    if (s.cloud.downloadUrl.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            R.string.startup_update_link_missing,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    } else {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.cloud.downloadUrl))
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.startup_update_now), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                            ),
                            onClick = { onContinue() },
                        ) {
                            Text(
                                text = stringResource(R.string.startup_continue),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                VerifyState.Failure -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                (context as? android.app.Activity)?.finishAffinity()
                            },
                        ) {
                            Text(stringResource(R.string.startup_exit), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                            ),
                            onClick = { refreshGeneration++ },
                        ) {
                            Text(stringResource(R.string.startup_retry), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                VerifyState.Loading -> Unit
            }
        }
    }
}

@Composable
private fun LoadingContent(colors: androidx.compose.material3.ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        ) {
            Text(
                text = stringResource(R.string.startup_announcement_badge),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.startup_announcement_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.startup_loading_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoTile(
                label = stringResource(R.string.startup_version_label),
                value = "—",
                accent = colors.primary,
                modifier = Modifier.weight(1f),
            )
            InfoTile(
                label = stringResource(R.string.startup_author_label),
                value = "—",
                accent = colors.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.startup_announcement_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    text = stringResource(R.string.startup_loading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FailureContent(colors: androidx.compose.material3.ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        ) {
            Text(
                text = stringResource(R.string.startup_failed_badge),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.error,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.startup_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.error,
            )
            Text(
                text = stringResource(R.string.startup_failed_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.startup_failed_message),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
        }
    }
}
