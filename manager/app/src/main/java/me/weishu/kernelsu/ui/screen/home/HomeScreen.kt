package me.weishu.kernelsu.ui.screen.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.magica.MagicaService
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.PermissionManager
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val viewModel = viewModel<HomeViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val loadingDialog = rememberLoadingDialog()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasActivated by remember { mutableStateOf(false) }
    if (isCurrentPage) hasActivated = true

    // 第一次进入页面 + 任何权限变化（binder收到/died/授权结果/缓存清除）都重测
    LaunchedEffect(Unit) {
        // 首次加载
        viewModel.refresh()
        // 订阅 PermissionManager 的广播式变化，collectLatest 丢弃积压事件
        PermissionManager.permissionChanges().collectLatest {
            viewModel.refreshPermission()
        }
    }

    // 权限实时：每次页面回到前台（onStart/onResume）也强制刷新 ——
    // 比如用户切到 Shizuku App 授权了，再切回来也能立刻看到"工作中"；
    // 或者 Shizuku 服务被杀、权限到期失效，也能立刻显示"未工作"。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    scope.launch { viewModel.refreshPermission() }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val actions = HomeActions(
        onInstallClick = { navigator.push(Route.Permission) },
        onOpenUrl = uriHandler::openUri,
        onJailbreakClick = {
            navigator.push(Route.Permission)
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> HomePagerMiuix(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> HomePagerMaterial(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
