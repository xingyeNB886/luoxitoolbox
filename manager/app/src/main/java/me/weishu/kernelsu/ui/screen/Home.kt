package me.weishu.kernelsu.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.system.Os
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.component.DropdownItem
import me.weishu.kernelsu.ui.component.RebootListPopup
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.CloudUpdateManager
import me.weishu.kernelsu.ui.util.PermissionManager
import me.weishu.kernelsu.ui.util.getModuleCount
import me.weishu.kernelsu.ui.util.getSELinuxStatus
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.reboot
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val checkUpdate = prefs.getBoolean("check_update", true)
    val themeMode = prefs.getInt("color_mode", 0)
    val signatureInvalid = prefs.getBoolean("signature_invalid", false)

    // 云端数据（公告、历史版本、版本检测共用）
    val cloudData by produceState(initialValue = CloudUpdateManager.CloudData()) {
        value = withContext(Dispatchers.IO) {
            CloudUpdateManager.fetchCloudData()
        }
    }

    // 强制更新检测：云端版本 > 本地版本 或 签名校验失败（防篡改）
    val localVersion = CloudUpdateManager.getLocalVersion()
    val cloudVersion = cloudData.internalVersion
    val showForceUpdate = checkUpdate && cloudVersion > 0 && (cloudVersion > localVersion || signatureInvalid)

    // 强制更新弹窗
    if (showForceUpdate) {
        ForceUpdateDialog(
            localVersion = localVersion,
            cloudVersion = cloudVersion,
            downloadUrl = cloudData.downloadUrl,
            themeMode = themeMode,
            signatureInvalid = signatureInvalid
        )
    }

    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                hazeState = hazeState,
                hazeStyle = hazeStyle,
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
                val mainState = LocalMainPagerState.current

                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(
                        onClickInstall = {
                            navigator.push(Route.Permission)
                        },
                        onClickSuperuser = {
                            mainState.animateToPage(1)
                        },
                        onclickModule = {
                            mainState.animateToPage(2)
                        },
                        themeMode = themeMode
                    )

                    InfoCard()
                    // 公告卡片 - 从QQ收藏读取
                    AnnouncementCard(announcement = cloudData.announcement)
                    // 历史版本卡片 - 从QQ收藏读取
                    VersionHistoryCard(versionHistory = cloudData.versionHistory)
                }
                Spacer(Modifier.height(bottomInnerPadding))
            }
        }
    }
}

/**
 * 强制更新弹窗 - 符合应用主题UI
 * 只有两个选择：退出（左）和 更新（右），在右下角显示
 */
@Composable
fun ForceUpdateDialog(
    localVersion: Int,
    cloudVersion: Int,
    downloadUrl: String,
    themeMode: Int,
    signatureInvalid: Boolean = false
) {
    val showDialog = remember { mutableStateOf(true) }
    val context = LocalContext.current

    val localVerStr = "${localVersion / 100}.${(localVersion % 100) / 10}.${localVersion % 10}"
    val cloudVerStr = "${cloudVersion / 100}.${(cloudVersion % 100) / 10}.${cloudVersion % 10}"

    val title = if (signatureInvalid) "安全警告" else "发现新版本"
    val message = if (signatureInvalid) {
        "检测到应用签名异常，可能存在安全风险，请更新到官方版本后使用。"
    } else {
        "检测到新版本，请更新后使用。"
    }

    SuperDialog(
        show = showDialog,
        title = title,
        onDismissRequest = { /* 强制更新，不可关闭 */ },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "当前版本：$localVerStr",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "最新版本：$cloudVerStr",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(20.dp))
                // 按钮在右下角：退出（左）更新（右）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "退出",
                        onClick = {
                            // 退出应用
                            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                it.putExtra("exit", true)
                            }
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "更新",
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                // 如果无法打开链接，尝试用浏览器
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
fun RebootDropdownItem(
    @StringRes id: Int, reason: String = "",
    showTopPopup: MutableState<Boolean>,
    optionSize: Int,
    index: Int,
) {
    DropdownItem(
        text = stringResource(id),
        optionSize = optionSize,
        onSelectedIndexChange = {
            reboot(reason)
            showTopPopup.value = false
        },
        index = index
    )
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    hazeStyle: HazeStyle,
) {
    TopAppBar(
        modifier = Modifier.hazeEffect(hazeState) {
            style = hazeStyle
            blurRadius = 30.dp
            noiseFactor = 0f
        },
        color = Color.Transparent,
        title = stringResource(R.string.app_name),
        actions = {
            RebootListPopup(
                modifier = Modifier.padding(end = 16.dp),
            )
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun StatusCard(
    onClickInstall: () -> Unit = {},
    onClickSuperuser: () -> Unit = {},
    onclickModule: () -> Unit = {},
    themeMode: Int,
) {
    var isWorking by remember { mutableStateOf(false) }
    var grantLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isWorking = PermissionManager.isAnyGranted()
        grantLabel = PermissionManager.getGrantLabel()
    }

    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            isWorking = PermissionManager.isAnyGranted()
            grantLabel = PermissionManager.getGrantLabel()
        }
        PermissionManager.addOnChangeListener(listener)
        onDispose { PermissionManager.removeOnChangeListener(listener) }
    }

    Column(
        modifier = Modifier
    ) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = CardDefaults.defaultColors(
                    color = if (isWorking) {
                        when {
                            isDynamicColor -> colorScheme.secondaryContainer
                            isInDarkTheme(themeMode) -> Color(0xFF1A3825)
                            else -> Color(0xFFDFFAE4)
                        }
                    } else {
                        when {
                            isDynamicColor -> colorScheme.errorContainer
                            isInDarkTheme(themeMode) -> Color(0XFF310808)
                            else -> Color(0xFFF8E2E2)
                        }
                    }
                ),
                onClick = {
                    onClickInstall()
                },
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Tilt
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(38.dp, 45.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            modifier = Modifier.size(170.dp),
                            imageVector = if (isWorking) {
                                Icons.Rounded.CheckCircleOutline
                            } else {
                                Icons.Rounded.ErrorOutline
                            },
                            tint = if (isWorking) {
                                if (isDynamicColor) {
                                    colorScheme.primary.copy(alpha = 0.8f)
                                } else {
                                    Color(0xFF36D167)
                                }
                            } else {
                                if (isDynamicColor) {
                                    colorScheme.error
                                } else {
                                    Color(0xFFF72727)
                                }
                            },
                            contentDescription = null
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(all = 16.dp)
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = workingText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = summaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.superuser),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = getSuperuserCount().toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.module),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = getModuleCount().toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 公告卡片 - 从QQ收藏读取公告内容
 */
@Composable
fun AnnouncementCard(announcement: String) {
    val displayText = announcement.ifBlank {
        stringResource(R.string.home_support_content)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicComponent(
            title = stringResource(R.string.home_support_title),
            summary = displayText,
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Link,
                    tint = colorScheme.onSurface,
                    contentDescription = null
                )
            },
            insideMargin = PaddingValues(18.dp)
        )
    }
}

/**
 * 历史版本卡片 - 从QQ收藏读取历史版本内容
 */
@Composable
fun VersionHistoryCard(versionHistory: String) {
    val displayText = versionHistory.ifBlank {
        stringResource(R.string.home_click_to_learn_kernelsu)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicComponent(
            title = stringResource(R.string.home_learn_kernelsu),
            summary = displayText,
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Link,
                    tint = colorScheme.onSurface,
                    contentDescription = null
                )
            },
            insideMargin = PaddingValues(18.dp)
        )
    }
}

@Composable
private fun InfoCard() {
    @Composable
    fun InfoText(
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
        )
    }
    Card {
        val context = LocalContext.current
        val uname = Os.uname()
        val managerVersion = getManagerVersion(context)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            InfoText(
                title = stringResource(R.string.home_kernel),
                content = uname.release
            )
            InfoText(
                title = stringResource(R.string.home_manager_version),
                content = "${managerVersion.first} (${managerVersion.second})"
            )
            InfoText(
                title = stringResource(R.string.home_fingerprint),
                content = Build.FINGERPRINT
            )
            InfoText(
                title = stringResource(R.string.home_selinux_status),
                content = getSELinuxStatus(),
                bottomPadding = 0.dp
            )
        }
    }
}

fun getManagerVersion(context: Context): Pair<String, Long> {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return Pair(packageInfo.versionName!!, versionCode)
}