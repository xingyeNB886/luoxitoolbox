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
                // 1. 先走官方 SDK 接口（可能弹授权框，也可能因无 Provider 不弹）
                val already = PermissionManager.requestShizukuPermission(10001)
                if (already) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.permission_shizuku_granted, Toast.LENGTH_SHORT).show()
                    }
                    PermissionManager.invalidateCache()
                    doRefresh()
                    return@launch
                }

                // 2. 如果 SDK 没能弹框（90% 情况是无 Provider 不弹）——
                //    引导用户去 Shizuku App 里手动找"洛茜工具箱 → 开启授权"，
                //    同时启动每 2s 轮询，直到检测到授权才停止（最多 2 分钟）。
                val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    || PermissionManager.isShizukuGranted()
                if (!running) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.permission_shizuku_not_installed, Toast.LENGTH_LONG).show()
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

                // 3. 轮询检测：每 2s 重测一次，检测到立刻刷新
                PermissionManager.shizukuGrantPollingFlow(maxRounds = 60)
                    .collect { granted ->
                        doRefresh()
                        if (granted) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.permission_shizuku_granted, Toast.LENGTH_SHORT).show()
                            }
                            return@collect
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
