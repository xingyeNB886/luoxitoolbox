package com.sukisu.ultra.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.CardConfig
import com.sukisu.ultra.ui.theme.CardConfig.cardElevation
import com.sukisu.ultra.ui.theme.getCardColors
import com.sukisu.ultra.ui.util.PermissionGrantType
import com.sukisu.ultra.ui.util.PermissionManager
import com.sukisu.ultra.ui.util.rootAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import rikka.shizuku.Shizuku

/**
 * 洛茜工具箱 · 权限授权页。
 *
 * 与旧版工具箱保持一致：Root / Shizuku 授权、初始化、内置 Shizuku 安装。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun PermissionScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    var grantType by remember { mutableStateOf(PermissionGrantType.NONE) }
    var rootGranted by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun doRefresh() {
        refreshing = true
        val grant = PermissionManager.checkGrantType(forceRefresh = true)
        grantType = grant
        rootGranted = PermissionManager.isRootGranted()
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuGranted = PermissionManager.isShizukuGranted()
        refreshing = false
    }

    LaunchedEffect(Unit) {
        doRefresh()
    }

    // 每次回到前台重新检测（覆盖在 Shizuku 列表授权后返回本页的场景）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { doRefresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 监听 Shizuku 授权回调，用户在弹窗点"允许"后立即刷新
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 10001) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                scope.launch {
                    PermissionManager.invalidateCache()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (granted) R.string.permission_shizuku_granted else R.string.permission_shizuku_not_granted,
                            Toast.LENGTH_SHORT
                        ).show()
                        doRefresh()
                    }
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val cardAlpha = CardConfig.cardAlpha

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_screen_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cardColor.copy(alpha = cardAlpha),
                    scrolledContainerColor = cardColor.copy(alpha = cardAlpha)
                ),
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = { scope.launch { doRefresh() } },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.permission_refresh_button)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            PermissionCardItem(
                title = stringResource(R.string.permission_root_title),
                summary = stringResource(R.string.permission_root_summary),
                icon = Icons.Filled.Security,
                granted = rootGranted,
                grantedString = stringResource(R.string.permission_root_granted),
                notGrantedString = stringResource(R.string.permission_root_not_granted),
                buttonString = stringResource(R.string.permission_root_request_button),
                onClickButton = {
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { rootAvailable() }.getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) R.string.permission_root_granted else R.string.permission_root_not_granted,
                                Toast.LENGTH_SHORT
                            ).show()
                            PermissionManager.invalidateCache()
                            doRefresh()
                        }
                    }
                }
            )

            PermissionCardItem(
                title = stringResource(R.string.permission_shizuku_title),
                summary = stringResource(R.string.permission_shizuku_summary),
                icon = Icons.Outlined.AdminPanelSettings,
                granted = shizukuGranted,
                grantedString = stringResource(R.string.permission_shizuku_granted),
                notGrantedString = if (shizukuRunning) {
                    stringResource(R.string.permission_shizuku_not_granted)
                } else {
                    stringResource(R.string.permission_shizuku_not_installed)
                },
                buttonString = stringResource(R.string.permission_shizuku_request_button),
                onClickButton = {
                    scope.launch {
                        val already = PermissionManager.requestShizukuPermission(10001)
                        if (already) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    R.string.permission_shizuku_granted,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            PermissionManager.invalidateCache()
                            doRefresh()
                            return@launch
                        }

                        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                            || PermissionManager.isShizukuGranted()
                        if (!running) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    R.string.permission_shizuku_not_installed,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.shizuku_permission_tip),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        PermissionManager.shizukuGrantPollingFlow(maxRounds = 60)
                            .collect { granted ->
                                doRefresh()
                                if (granted) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            R.string.permission_shizuku_granted,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    return@collect
                                }
                            }
                    }
                }
            )

            // 初始化卡片
            InitCard(
                granted = rootGranted || shizukuGranted,
                scope = scope
            )

            // Shizuku 安装卡片
            ShizukuInstallCard(scope = scope)

            if (grantType == PermissionGrantType.BOTH) {
                ElevatedCard(
                    colors = getCardColors(MaterialTheme.colorScheme.tertiaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.permission_grant_type_both),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 权限卡片：标题 + 副标题 + 状态 + 授权按钮
 */
/**
 * Shizuku 安装卡片：从内置 assets/shizuku.apk 拷贝到缓存后触发系统安装。
 */
@Composable
private fun ShizukuInstallCard(scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var installing by remember { mutableStateOf(false) }

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_shizuku_install_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_shizuku_install_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    enabled = !installing,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            installing = true
                            try {
                                // 从内置 assets 读取 Shizuku APK，写入应用缓存目录后触发安装
                                val apkFile = File(context.cacheDir, "shizuku.apk")
                                context.assets.open("shizuku.apk").use { input ->
                                    apkFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                withContext(Dispatchers.Main) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                apkFile
                                            ),
                                            "application/vnd.android.package-archive"
                                        )
                                        addFlags(
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                        )
                                    }
                                    context.startActivity(intent)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.apk_read_error, e.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } finally {
                                installing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (installing) stringResource(R.string.preparing) else stringResource(R.string.install_shizuku))
                }
            }
        }
    }
}

@Composable
private fun PermissionCardItem(
    title: String,
    summary: String,
    icon: ImageVector,
    granted: Boolean,
    grantedString: String,
    notGrantedString: String,
    buttonString: String,
    onClickButton: () -> Unit,
) {
    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (granted) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = grantedString,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(summary)
                    append("\n")
                    append(if (granted) grantedString else notGrantedString)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!granted) {
                    Button(onClick = onClickButton) {
                        Text(buttonString)
                    }
                } else {
                    OutlinedButton(
                        enabled = false,
                        onClick = {},
                        colors = ButtonDefaults.outlinedButtonColors(
                            disabledContainerColor = Color.Transparent
                        )
                    ) {
                        Text(grantedString)
                    }
                }
            }
        }
    }
}

/**
 * 初始化卡片：点一次按钮就创建 luoxi 目录 + 伪装系统文件（幂等）
 */
@Composable
private fun InitCard(
    granted: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    var initializing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.init_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(stringResource(R.string.init_summary_prefix))
                    append("\n")
                    append(
                        when {
                            done -> stringResource(R.string.init_status_done)
                            !granted -> stringResource(R.string.init_status_no_grant)
                            else -> stringResource(R.string.init_status_ready)
                        }
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    enabled = granted && !initializing && !done,
                    onClick = {
                        scope.launch {
                            initializing = true
                            val ok = PermissionManager.ensureInitFiles()
                            initializing = false
                            done = ok
                            Toast.makeText(
                                context,
                                if (ok) context.getString(R.string.init_success)
                                else context.getString(R.string.init_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(
                        text = when {
                            initializing -> stringResource(R.string.init_button_initializing)
                            done -> stringResource(R.string.init_button_done)
                            else -> stringResource(R.string.init_button)
                        }
                    )
                }
            }
        }
    }
}

