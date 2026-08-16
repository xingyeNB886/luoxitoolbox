package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.ui.util.PermissionGrantType
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val uapiMismatch: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
    // 洛茜工具箱：权限授权状态
    val permissionGrant: PermissionGrantType = PermissionGrantType.NONE,
    // 洛茜工具箱：buildState 过程中发生的错误（非 null 时会在首页顶部显示提示）
    val buildError: String? = null,
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    // 洛茜工具箱：只要有权限（Root 或 Shizuku）就算"全功能"
    val isFullFeatured: Boolean
        get() = true

    val showGkiWarning: Boolean
        get() = false

    val showRequireKernelWarning: Boolean
        get() = false

    val showUAPIMisMatchWarning: Boolean
        get() = false

    val showRootWarning: Boolean
        get() = false

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val showVersionMismatchWarning: Boolean
        get() = ksuVersion != null && ksuVersion.toLong() != currentManagerVersionCode

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
)
