package me.weishu.kernelsu.ui.screen.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.util.PermissionGrantType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreenMaterial(
    state: PermissionUiState,
    actions: PermissionScreenActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = { TopBar(scrollBehavior, actions, state.refreshing) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionCardMaterial(
                title = stringResource(R.string.permission_root_title),
                summary = stringResource(R.string.permission_root_summary),
                icon = Icons.Outlined.Security,
                granted = state.rootGranted,
                grantedString = stringResource(R.string.permission_root_granted),
                notGrantedString = stringResource(R.string.permission_root_not_granted),
                buttonString = stringResource(R.string.permission_root_request_button),
                onClickButton = actions.onRequestRoot
            )

            PermissionCardMaterial(
                title = stringResource(R.string.permission_shizuku_title),
                summary = stringResource(R.string.permission_shizuku_summary),
                icon = Icons.Outlined.AdminPanelSettings,
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
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = containerColor,
                    contentColor = MaterialTheme.colorScheme.contentColorFor(containerColor)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.permission_grant_type_both),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: PermissionScreenActions,
    refreshing: Boolean,
) {
    LargeTopAppBar(
        title = { Text(stringResource(R.string.permission_screen_title)) },
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            if (refreshing) {
                Box(Modifier.padding(end = 16.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(22.dp).height(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                IconButton(onClick = actions.onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.permission_refresh_button))
                }
            }
        },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun PermissionCardMaterial(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    granted: Boolean,
    grantedString: String,
    notGrantedString: String,
    buttonString: String,
    onClickButton: () -> Unit,
) {
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = MaterialTheme.colorScheme.contentColorFor(containerColor)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListItem(
                leadingContent = {
                    Icon(icon, contentDescription = title)
                },
                overlineContent = null,
                headlineContent = {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                },
                supportingContent = {
                    Column {
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (granted) grantedString else notGrantedString,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                trailingContent = {
                    if (!granted) {
                        Button(
                            onClick = onClickButton,
                            colors = ButtonDefaults.buttonColors()
                        ) {
                            Text(buttonString)
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor,
                    leadingContentColor = contentColor,
                    supportingContentColor = contentColor.copy(alpha = 0.8f),
                ),
            )
        }
    }
}
