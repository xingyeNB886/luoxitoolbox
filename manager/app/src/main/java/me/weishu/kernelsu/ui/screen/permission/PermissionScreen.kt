package me.weishu.kernelsu.ui.screen.permission

import android.content.pm.PackageManager
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.PermissionGrantType
import me.weishu.kernelsu.ui.util.PermissionManager
import me.weishu.kernelsu.ui.util.rootAvailable
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import rikka.shizuku.Shizuku

@Composable
fun PermissionScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val homeViewModel: HomeViewModel = viewModel()

    var state by remember { mutableStateOf(PermissionUiState()) }

    suspend fun doRefresh() {
        state = state.copy(refreshing = true)
        val grant = PermissionManager.checkGrantType(forceRefresh = true)
        state = state.copy(
            grantType = grant,
            rootGranted = PermissionManager.isRootGranted(),
            shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
            shizukuGranted = PermissionManager.isShizukuGranted(),
            refreshing = false,
        )
        homeViewModel.refreshPermission()
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

    val actions = PermissionScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRequestRoot = {
            scope.launch(Dispatchers.IO) {
                // 触发一次 rootAvailable 弹出 root 授权对话框
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
        },
        onRequestShizuku = {
            scope.launch {
                val already = PermissionManager.requestShizukuPermission(10001)
                if (already) {
                    Toast.makeText(context, R.string.permission_shizuku_granted, Toast.LENGTH_SHORT).show()
                    PermissionManager.invalidateCache()
                    doRefresh()
                } else {
                    // Shizuku 未运行时提示
                    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    if (!running) {
                        Toast.makeText(context, R.string.permission_shizuku_not_installed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        onRefresh = {
            scope.launch { doRefresh() }
        }
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> PermissionScreenMiuix(state, actions)
        UiMode.Material -> PermissionScreenMaterial(state, actions)
    }
}
