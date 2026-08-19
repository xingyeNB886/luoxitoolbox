package com.sukisu.ultra.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.FileProvider
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.theme.CardConfig
import com.sukisu.ultra.ui.theme.CardConfig.cardElevation
import com.sukisu.ultra.ui.theme.getCardColors
import com.sukisu.ultra.ui.util.PermissionGrantType
import com.sukisu.ultra.ui.util.PermissionManager
import com.sukisu.ultra.ui.util.WirelessAdbDiscovery
import com.sukisu.ultra.ui.util.WirelessAdbManager
import com.sukisu.ultra.ui.util.rootAvailable
import com.sukisu.ultra.service.WirelessAdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

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

            // Shizuku 安装卡片（内置 APK）
            ShizukuInstallCard(scope = scope)

            // 无线调试卡片（内置 Shizuku 替代方案）
            WirelessDebuggingCard(scope = scope)

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
 * 无线调试卡片：内置 Shizuku 替代方案
 *
 * 流程：授予通知权限 → 开启无线调试 → 获取配对码 → 输入配对码 → 建立 ADB 连接
 */
@Composable
private fun WirelessDebuggingCard(scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current

    var pairingCode by remember { mutableStateOf("") }
    var hostPort by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(WirelessAdbManager.isPaired()) }
    var statusMsg by remember { mutableStateOf("") }

    // 通知权限
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, R.string.wireless_adb_notif_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.wireless_adb_notif_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true // API 32 及以下默认有通知权限
        }
    }

    /** 执行配对并更新配对输入框状态 */
    fun doPair(code: String, host: String, port: Int) {
        pairing = true
        statusMsg = context.getString(R.string.wireless_adb_pairing)
        scope.launch(Dispatchers.IO) {
            val result = WirelessAdbManager.pair(host, port, code)
            withContext(Dispatchers.Main) {
                pairing = false
                when (result) {
                    is WirelessAdbManager.PairResult.Success -> {
                        paired = true
                        statusMsg = context.getString(R.string.wireless_adb_pair_success)
                        Toast.makeText(
                            context,
                            R.string.wireless_adb_pair_success,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is WirelessAdbManager.PairResult.Failure -> {
                        statusMsg = context.getString(
                            R.string.wireless_adb_pair_failed, result.message
                        )
                    }
                }
            }
        }
    }

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
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = stringResource(R.string.wireless_adb_card_title),
                    tint = if (paired) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.wireless_adb_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.wireless_adb_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (paired) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.wireless_adb_status_paired),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.wireless_adb_card_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // 步骤引导
            Text(
                text = stringResource(R.string.wireless_adb_step1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wireless_adb_step2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wireless_adb_step3),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wireless_adb_step4),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // 从通知栏启动（类似 Shizuku）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = checkNotificationPermission() && !pairing,
                    onClick = {
                        WirelessAdbService.start(context)
                        Toast.makeText(
                            context,
                            R.string.wireless_adb_notif_waiting,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.wireless_adb_start_notif))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 按钮：授予通知权限 + 打开无线调试设置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !checkNotificationPermission() && !pairing,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                ) {
                    Text(stringResource(R.string.wireless_adb_notif_grant))
                }
                OutlinedButton(
                    enabled = !pairing,
                    onClick = {
                        try {
                            // Android 11+ 无线调试设置页
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法打开设置", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.wireless_adb_open_settings))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 输入框：配对码
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.wireless_adb_pair_code)) },
                singleLine = true,
                enabled = !pairing && !paired,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // 输入框：IP:Port
            OutlinedTextField(
                value = hostPort,
                onValueChange = { hostPort = it },
                label = { Text(stringResource(R.string.wireless_adb_host_port)) },
                singleLine = true,
                enabled = !pairing && !paired,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // 状态消息
            if (statusMsg.isNotBlank()) {
                Text(
                    text = statusMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (paired) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }

            // 配对按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (pairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Button(
                    enabled = !pairing && !paired && pairingCode.length == 6,
                    onClick = {
                        val code = pairingCode
                        // 如果填写了 IP:端口 则手动配对，否则自动发现端口
                        val manualHostPort = hostPort.trim()
                        if (manualHostPort.contains(":")) {
                            val parts = manualHostPort.split(":")
                            val host = parts[0].trim()
                            val port = parts[1].trim().toIntOrNull()
                            if (parts.size != 2 || host.isEmpty() || port == null || port <= 0 || port > 65535) {
                                statusMsg = context.getString(R.string.wireless_adb_invalid_input)
                                return@Button
                            }
                            doPair(code, host, port)
                            return@Button
                        }

                        // 自动发现（和 Shizuku 一样：只输配对码，自动拿 IP 和端口）
                        pairing = true
                        statusMsg = context.getString(R.string.wireless_adb_pairing)
                        scope.launch {
                            val port = WirelessAdbDiscovery.discoverPairingPortWithTimeout(context)
                            if (port == null) {
                                pairing = false
                                statusMsg = context.getString(
                                    R.string.wireless_adb_notif_discover_failed
                                )
                            } else {
                                pairing = false
                                statusMsg = ""
                                doPair(code, "127.0.0.1", port)
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (paired) stringResource(R.string.wireless_adb_status_paired)
                        else stringResource(R.string.wireless_adb_start_pairing)
                    )
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

/**
 * Shizuku 安装卡片：从内置 assets/shizuku.apk 拷贝到缓存后触发系统安装
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
                    }
                ) {
                    Text(
                        text = if (installing) stringResource(R.string.preparing)
                        else stringResource(R.string.install_shizuku)
                    )
                }
            }
        }
    }
}
