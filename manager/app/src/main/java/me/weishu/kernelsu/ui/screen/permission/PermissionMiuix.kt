package me.weishu.kernelsu.ui.screen.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.PermissionGrantType
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
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun PermissionScreenMiuix(
    state: PermissionUiState,
    actions: PermissionScreenActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
                actions = actions,
                refreshing = state.refreshing,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        PermissionCardMiuix(
                            title = stringResource(R.string.permission_root_title),
                            summary = stringResource(R.string.permission_root_summary),
                            icon = Icons.Rounded.Security,
                            granted = state.rootGranted,
                            grantedString = stringResource(R.string.permission_root_granted),
                            notGrantedString = stringResource(R.string.permission_root_not_granted),
                            buttonString = stringResource(R.string.permission_root_request_button),
                            onClickButton = actions.onRequestRoot
                        )
                        PermissionCardMiuix(
                            title = stringResource(R.string.permission_shizuku_title),
                            summary = stringResource(R.string.permission_shizuku_summary),
                            icon = Icons.Rounded.AdminPanelSettings,
                            granted = state.shizukuGranted,
                            grantedString = stringResource(R.string.permission_shizuku_granted),
                            notGrantedString = if (state.shizukuRunning) {
                                stringResource(R.string.permission_shizuku_not_granted)
                            } else {
                                stringResource(R.string.permission_shizuku_not_installed)
                            },
                            buttonString = stringResource(R.string.permission_shizuku_request_button),
                            onClickButton = actions.onRequestShizuku
                        )
                        if (state.grantType == PermissionGrantType.BOTH) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.defaultColors(
                                    color = when {
                                        isDynamicColor -> colorScheme.tertiaryContainer
                                        isInDarkTheme() -> Color(0xFF2B2510)
                                        else -> Color(0xFFFBF3D8)
                                    }
                                )
                            ) {
                                BasicComponent(
                                    title = stringResource(R.string.permission_grant_type_both),
                                    summary = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
    actions: PermissionScreenActions,
    refreshing: Boolean,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.permission_screen_title),
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBackIosNew,
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.permission_refresh_button),
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun PermissionCardMiuix(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    granted: Boolean,
    grantedString: String,
    notGrantedString: String,
    buttonString: String,
    onClickButton: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = when {
                !granted -> if (isInDarkTheme()) Color(0xFF1C1B1F) else colorScheme.surface
                isDynamicColor -> colorScheme.secondaryContainer
                isInDarkTheme() -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            }
        ),
    ) {
        BasicComponent(
            title = title,
            summary = buildString {
                append(summary)
                append("\n")
                append(if (granted) grantedString else notGrantedString)
            },
            startAction = {
                Icon(
                    icon,
                    title,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = if (granted) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                )
            },
            endActions = {
                if (!granted) {
                    TextButton(
                        text = buttonString,
                        onClick = onClickButton,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }
}
