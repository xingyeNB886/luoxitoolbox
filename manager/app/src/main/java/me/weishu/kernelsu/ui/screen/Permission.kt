package me.weishu.kernelsu.ui.screen

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.FileManagerUtils
import me.weishu.kernelsu.ui.util.PermissionGrantType
import me.weishu.kernelsu.ui.util.PermissionManager
import me.weishu.kernelsu.ui.util.rootAvailable
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.File

/**
 * 洛茜工具箱 · 权限授权页。
 *
 * 适配 v3.1.0 结构（不依赖 ksu-fresh 独有的 UiMode / HomeViewModel / BlurredBar 等），
 * 直接调用 [PermissionManager] 检测 Root / Shizuku 授权状态。
 */
@Composable
fun PermissionScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                },
                color = Color.Transparent,
                title = stringResource(R.string.permission_screen_title),
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.padding(start = 16.dp),
                        onClick = dropUnlessResumed { navigator.pop() }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBackIosNew,
                            contentDescription = null,
                            tint = colorScheme.onBackground
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
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.permission_refresh_button),
                                tint = colorScheme.onBackground
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp)
                .hazeSource(state = hazeState),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.permission_screen_subtitle),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    PermissionCardItem(
                        title = stringResource(R.string.permission_root_title),
                        summary = stringResource(R.string.permission_root_summary),
                        icon = Icons.Rounded.Security,
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
                        icon = Icons.Rounded.AdminPanelSettings,
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
                                        "请打开 Shizuku → 找到「洛茜工具箱」→ 开启授权；授权后本页会自动检测（最多 2 分钟）",
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

                    // 初始化卡片：放在 Shizuku 权限框下面
                    InitCard(
                        granted = rootGranted || shizukuGranted,
                        scope = scope
                    )

                    // Shizuku 安装卡片：放在初始化卡片下方
                    ShizukuInstallCard(scope = scope)

                    if (grantType == PermissionGrantType.BOTH) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.defaultColors(
                                color = colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.permission_grant_type_both),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 初始化卡片（位于 Shizuku 权限框下方）
 *
 * 不做任何自动检测——点一次按钮就创建（已存在的自动跳过）：
 * luoxi 目录 + Android/data 下的伪装系统文件。
 * 卡片样式与功能区一致：Card { Column(padding 18dp) { 标题; 副标题; Row(右对齐){ TextButton } } }
 */
@Composable
private fun InitCard(
    granted: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    var initializing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "初始化",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("请先授权权限，再进行初始化。仅执行一次即可，之后无需再次执行。")
                    append("\n")
                    append("将创建 luoxi 目录（含 备份/、文件输出/ 子目录）。")
                    append("\n")
                    append(
                        when {
                            done -> "已初始化，无需重复执行"
                            !granted -> "尚未授权权限，请先授权上方任意一种权限"
                            else -> "点击右侧按钮开始初始化"
                        }
                    )
                },
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
                        initializing -> "初始化中…"
                        done -> "已完成"
                        else -> "初始化"
                    },
                    enabled = granted && !initializing && !done,
                    onClick = {
                        scope.launch {
                            initializing = true
                            val ok = FileManagerUtils.ensureInitFiles()
                            initializing = false
                            done = ok
                            Toast.makeText(
                                context,
                                if (ok) "初始化完成" else "初始化失败，请检查权限",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/**
 * Shizuku 安装卡片（位于初始化卡片下方）
 *
 * 主标题：没有安装 Shizuku？
 * 副标题：Shizuku 可提供 ADB 级权限，无需 Root 即可使用本工具全部功能。
 * 按钮：安装 Shizuku —— 从内置 assets/shizuku.apk 拷贝到应用缓存后触发系统安装。
 * 首次安装可能需要授予"安装未知应用"权限。
 * 卡片样式与功能区一致。
 */
@Composable
private fun ShizukuInstallCard(scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var installing by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "没有安装 Shizuku？",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Shizuku 可提供 ADB 级别权限，无需 Root 即可使用本工具全部功能。点击右侧按钮直接安装内置的 Shizuku 安装包。",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = if (installing) "准备中…" else "安装 Shizuku",
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
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                apkFile
                                            ),
                                            "application/vnd.android.package-archive"
                                        )
                                        addFlags(
                                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        )
                                    }
                                    context.startActivity(intent)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "安装包读取失败：${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } finally {
                                installing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun PermissionCardItem(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    granted: Boolean,
    grantedString: String,
    notGrantedString: String,
    buttonString: String,
    onClickButton: () -> Unit,
) {
    // 与功能区卡片样式一致：Card { Column(padding 18dp) { 标题; 副标题; Row(右对齐){ TextButton } } }
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(summary)
                    append("\n")
                    append(if (granted) grantedString else notGrantedString)
                },
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!granted) {
                    TextButton(
                        text = buttonString,
                        onClick = onClickButton,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                } else {
                    TextButton(
                        text = grantedString,
                        enabled = false,
                        onClick = {},
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}
