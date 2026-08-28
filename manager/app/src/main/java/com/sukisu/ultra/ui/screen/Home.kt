package com.sukisu.ultra.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.InstallScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PermissionScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.KernelVersion
import com.sukisu.ultra.Natives
import com.sukisu.ultra.R
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.getKernelVersion
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.component.KsuIsValid
import com.sukisu.ultra.ui.component.rememberConfirmDialog
import com.sukisu.ultra.ui.theme.CardConfig
import com.sukisu.ultra.ui.theme.CardConfig.cardElevation
import com.sukisu.ultra.ui.theme.getCardColors
import com.sukisu.ultra.ui.util.getKpmModuleCount
import com.sukisu.ultra.ui.util.getModuleCount
import com.sukisu.ultra.ui.util.getSuSFS
import com.sukisu.ultra.ui.util.getSuperuserCount
import com.sukisu.ultra.ui.util.PermissionManager
import com.sukisu.ultra.ui.util.checkNewVersion
import com.sukisu.ultra.ui.util.getRealResolution
import com.sukisu.ultra.ui.util.module.LatestVersionInfo
import com.sukisu.ultra.ui.util.reboot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Destination<RootGraph>(start = true)
@Composable
fun HomeScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var showKpmInfo by rememberSaveable { mutableStateOf(true) }

    // 从 SharedPreferences 加载设置
    LaunchedEffect(Unit) {
        showKpmInfo = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("show_kpm_info", true)
    }

    val kernelVersion = getKernelVersion()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val isManager = true
    val deviceModel = getDeviceModel()
    val ksuVersion: Int? = 12900
    val zako = false
    val isVersion = false
    val shouldTriggerRestart = false

    LaunchedEffect(shouldTriggerRestart) {
        if (shouldTriggerRestart) {
            val random = Random.nextInt(0, 100)
            if (random <= 95) {
                reboot()
            } else {
                ""
            }
        }
    }

    val scrollState = rememberScrollState()
    val debounceTime = 100L
    var lastScrollTime by remember { mutableLongStateOf(0L) }

    Scaffold(
        topBar = {
            TopBar(
                kernelVersion,
                onInstallClick = { navigator.navigate(InstallScreenDestination) },
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
                .disableOverscroll()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(top = 12.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (shouldTriggerRestart) {
                WarningCard(message = "zakozako")
                return@Column
            }

            StatusCard {
                navigator.navigate(PermissionScreenDestination)
            }

            val checkUpdate =
                LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("check_update", true)
            if (checkUpdate) {
                UpdateCard()
            }

            val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

            InfoCard()

            Spacer(Modifier.height(16.dp))
        }
    }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .debounce(debounceTime)
            .collect { isScrolling ->
                if (isScrolling) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastScrollTime > debounceTime) {
                        lastScrollTime = currentTime
                    }
                }
            }
    }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val latestVersionInfo = LatestVersionInfo()
    val newVersion by produceState(initialValue = latestVersionInfo) {
        value = withContext(Dispatchers.IO) {
            checkNewVersion()
        }
    }

    val currentVersionCode = getManagerVersion(context).second
    val newVersionCode = newVersion.versionCode
    val newVersionUrl = newVersion.downloadUrl
    val changelog = newVersion.changelog

    val uriHandler = LocalUriHandler.current
    val title = stringResource(id = R.string.module_changelog)
    val updateText = stringResource(id = R.string.module_update)

    AnimatedVisibility(
        visible = newVersionCode > currentVersionCode,
        enter = fadeIn() + expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ),
        exit = shrinkVertically() + fadeOut()
    ) {
        val updateDialog = rememberConfirmDialog(onConfirm = { uriHandler.openUri(newVersionUrl) })
        WarningCard(
            message = stringResource(id = R.string.new_version_available).format(newVersionCode),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            onClick = {
                if (changelog.isEmpty()) {
                    uriHandler.openUri(newVersionUrl)
                } else {
                    updateDialog.showConfirm(
                        title = title,
                        content = changelog,
                        markdown = true,
                        confirm = updateText
                    )
                }
            }
        )
    }
}

@Composable
fun RebootDropdownItem(@StringRes id: Int, reason: String = "") {
    DropdownMenuItem(
        text = { Text(stringResource(id)) },
        onClick = { reboot(reason) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    kernelVersion: KernelVersion,
    onInstallClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val cardAlpha = CardConfig.cardAlpha

    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = cardColor.copy(alpha = cardAlpha),
            scrolledContainerColor = cardColor.copy(alpha = cardAlpha)
        ),
        actions = {
            IconButton(onClick = onInstallClick) {
                Icon(
                    Icons.Filled.Archive,
                    contentDescription = stringResource(R.string.install),
                )
            }

            var showDropdown by remember { mutableStateOf(false) }
            KsuIsValid {
                IconButton(onClick = {
                    showDropdown = true
                }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(id = R.string.reboot)
                    )

                    DropdownMenu(expanded = showDropdown, onDismissRequest = {
                        showDropdown = false
                    }) {
                        RebootDropdownItem(id = R.string.reboot)

                        val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm?.isRebootingUserspaceSupported == true) {
                            RebootDropdownItem(id = R.string.reboot_userspace, reason = "userspace")
                        }
                        RebootDropdownItem(id = R.string.reboot_recovery, reason = "recovery")
                        RebootDropdownItem(id = R.string.reboot_bootloader, reason = "bootloader")
                        RebootDropdownItem(id = R.string.reboot_download, reason = "download")
                        RebootDropdownItem(id = R.string.reboot_edl, reason = "edl")
                    }
                }
            }
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun StatusCard(
    onClickGrant: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var grantLabel by remember { mutableStateOf("") }
    // 状态卡下方真实信息行（工具版本 / 超级用户数 / 模块数 / KPM模块数 / SUSFS支持）
    var infoVersion by remember { mutableStateOf("") }
    var superuserCount by remember { mutableStateOf(0) }
    var moduleCount by remember { mutableStateOf(0) }
    var kpmModuleCount by remember { mutableStateOf(0) }
    var susfsSupport by remember { mutableStateOf("") }

    // 重新检测授权状态并读取真实数据；可在任意线程调用（内部切 IO 检测 / Main 更新）
    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val working = PermissionManager.isAnyGranted()
            val label = PermissionManager.getGrantLabel()

            val (vName, vCode) = getManagerVersion(context)
            val version = if (vName.isNotBlank()) "$vName ($vCode)" else BuildConfig.VERSION_NAME

            // 真实查询：KSU 驱动在线时返回真实计数，离线时如实返回空/0
            val suCount = runCatching { getSuperuserCount() }.getOrDefault(0)
            val modCount = runCatching { getModuleCount() }.getOrDefault(0)

            // KPM 模块数（始终显示；无 KPM 驱动时如实显示 0）
            val kpmCount = runCatching { getKpmModuleCount() }.getOrDefault(0)

            // SusFS 支持状态（始终显示；空时显示"未知"）
            var susfs = runCatching { getSuSFS() }.getOrDefault("")
            if (susfs.isBlank()) {
                susfs = "Unknown"
            }

            withContext(Dispatchers.Main) {
                isWorking = working
                grantLabel = label
                infoVersion = version
                superuserCount = suCount
                moduleCount = modCount
                kpmModuleCount = kpmCount
                susfsSupport = susfs
            }
        }
    }

    // 首次进入 + 每次回到前台时刷新（覆盖从 Shizuku App 外部授权后返回的场景）
    LaunchedEffect(context) {
        refreshStatus()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 授权状态变化时自动刷新（Shizuku 授权弹窗回调 / Binder 变化）
    DisposableEffect(Unit) {
        val listener: () -> Unit = { refreshStatus() }
        PermissionManager.addOnChangeListener(listener)
        onDispose { PermissionManager.removeOnChangeListener(listener) }
    }

    val workingText = if (isWorking) {
        "${stringResource(id = R.string.permission_working)}$grantLabel"
    } else {
        stringResource(id = R.string.permission_not_working)
    }
    val summaryText = if (isWorking) {
        stringResource(id = R.string.permission_working_summary)
    } else {
        stringResource(id = R.string.permission_tap_to_grant)
    }

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClickGrant() }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isWorking) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.permission_working),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Column(Modifier.padding(start = 20.dp)) {
                    Text(
                        text = workingText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 状态卡下方信息行：标签+冒号+值，一行一句（原版样式）
                    Spacer(Modifier.height(8.dp))
                    InfoLine(
                        text = stringResource(R.string.home_working_version_full, infoVersion)
                    )
                    InfoLine(
                        text = stringResource(
                            R.string.home_superuser_count_full,
                            superuserCount
                        )
                    )
                    InfoLine(
                        text = stringResource(R.string.home_module_count_full, moduleCount)
                    )
                    InfoLine(
                        text = stringResource(R.string.home_kpm_module_full, kpmModuleCount)
                    )
                    val susfsTranslated = when (susfsSupport) {
                        "Supported" -> stringResource(R.string.status_supported)
                        "Not Supported" -> stringResource(R.string.status_not_supported)
                        else -> stringResource(R.string.status_unknown)
                    }
                    InfoLine(
                        text = stringResource(R.string.home_susfs_full, susfsTranslated)
                    )
                }
            } else {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = stringResource(R.string.permission_not_working),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )

                Column(Modifier.padding(start = 20.dp)) {
                    Text(
                        text = workingText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun WarningCard(
    message: String,
    color: Color = MaterialTheme.colorScheme.errorContainer,
    onClick: (() -> Unit)? = null
) {
    ElevatedCard(
        colors = getCardColors(color),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(onClick?.let { Modifier.clickable { it() } } ?: Modifier)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(28.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun ContributionCard() {
    val uriHandler = LocalUriHandler.current
    val links = listOf("https://github.com/ShirkNeko", "https://github.com/udochina")

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val randomIndex = Random.nextInt(links.size)
                    uriHandler.openUri(links[randomIndex])
                }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_ContributionCard_kernelsu),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_click_to_ContributionCard_kernelsu),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun LearnMoreCard() {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.home_learn_kernelsu_url)

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri(url)
                }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_learn_kernelsu),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_click_to_learn_kernelsu),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun DonateCard() {
    val uriHandler = LocalUriHandler.current

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://patreon.com/weishu")
                }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_support_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_support_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun InfoCard() {
    val context = LocalContext.current

    ElevatedCard(
        colors = getCardColors(MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .shadow(
                elevation = cardElevation,
                shape = MaterialTheme.shapes.large,
                spotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            @Composable
            fun InfoCardItem(
                label: String,
                content: String,
                icon: ImageVector = Icons.Default.Info,
                iconTint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ){
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true
                        )
                    }
                }
            }

            // 安卓版本（原内核版本，图标换色：绿色）
            val androidVersion = Build.VERSION.RELEASE
            InfoCardItem(
                "安卓版本",
                androidVersion,
                icon = Icons.Default.Android,
                iconTint = Color(0xFF3DDC84)
            )

            // 设备分辨率（原 Linux 行，图标换色：蓝色）—— 真实物理分辨率
            val (realWidth, realHeight) = context.getRealResolution()
            val densityDpi = context.resources.displayMetrics.densityDpi
            InfoCardItem(
                "设备分辨率",
                "$realWidth × $realHeight (${densityDpi}dpi)",
                icon = Icons.Default.PhoneAndroid,
                iconTint = Color(0xFF4285F4)
            )

            // 设备型号（图标换色：橙色）
            val deviceModel = getDeviceModel()
            InfoCardItem(
                stringResource(R.string.home_device_model),
                deviceModel,
                icon = Icons.Default.PhoneAndroid,
                iconTint = Color(0xFFFFA726)
            )

            // 工具箱版本（原管理器版本，图标换色：紫色）
            val managerVersion = getManagerVersion(context)
            InfoCardItem(
                "工具箱版本",
                "${managerVersion.first} (${managerVersion.second})",
                icon = Icons.Default.Settings,
                iconTint = Color(0xFFAB47BC)
            )
        }
    }
}

fun getManagerVersion(context: Context): Pair<String, Long> {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return Pair(packageInfo.versionName!!, versionCode)
}

@Preview
@Composable
private fun StatusCardPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard {}
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Preview
@Composable
private fun WarningCardPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WarningCard(message = "Warning message")
        WarningCard(
            message = "Warning message ",
            MaterialTheme.colorScheme.tertiaryContainer,
            onClick = {})
    }
}

@SuppressLint("PrivateApi")
private fun getDeviceModel(): String {
    return try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val getMethod = systemProperties.getMethod("get", String::class.java, String::class.java)
        val marketNameKeys = listOf(
            "ro.product.marketname",          // Xiaomi
            "ro.vendor.oplus.market.name",    // Oppo, OnePlus, Realme
            "ro.vivo.market.name",            // Vivo
            "ro.config.marketing_name"        // Huawei
        )
        for (key in marketNameKeys) {
            val marketName = getMethod.invoke(null, key, "") as String
            if (marketName.isNotEmpty()) {
                return marketName
            }
        }
        Build.DEVICE
    } catch (_: Exception) {
        Build.DEVICE
    }
}

private fun checkKpmConfigured(): Boolean {
    try {
        val process = Runtime.getRuntime().exec("su -c cat /proc/config.gz")
        val inputStream = process.inputStream
        val gzipInputStream = GZIPInputStream(inputStream)
        val reader = BufferedReader(InputStreamReader(gzipInputStream))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line?.contains("CONFIG_KPM=y") == true) {
                return true
            }
        }
        reader.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return false
}

@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.disableOverscroll(): Modifier = composed {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this
    } else {
        this
    }
}