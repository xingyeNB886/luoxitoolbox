package me.weishu.kernelsu.ui.screen.permission

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.util.PermissionGrantType

@Immutable
data class PermissionUiState(
    /** 当前授权类型 */
    val grantType: PermissionGrantType = PermissionGrantType.NONE,
    /** Shizuku 是否运行（binder 可 ping 通） */
    val shizukuRunning: Boolean = false,
    /** Shizuku 是否已授权 */
    val shizukuGranted: Boolean = false,
    /** Root 是否已授权 */
    val rootGranted: Boolean = false,
    /** 是否正在刷新 */
    val refreshing: Boolean = false,
)

@Immutable
data class PermissionScreenActions(
    val onBack: () -> Unit,
    val onRequestRoot: () -> Unit,
    val onRequestShizuku: () -> Unit,
    val onRefresh: () -> Unit,
)
