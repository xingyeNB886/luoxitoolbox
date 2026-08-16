package me.weishu.kernelsu.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.SystemInfo
import me.weishu.kernelsu.ui.screen.home.getManagerVersion
import me.weishu.kernelsu.ui.util.PermissionGrantType
import me.weishu.kernelsu.ui.util.PermissionManager
import me.weishu.kernelsu.ui.util.checkNewVersion
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import me.weishu.kernelsu.ui.util.resolveDeviceName
import me.weishu.kernelsu.ui.util.rootAvailable

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            // 洛茜工具箱：异步刷新权限授权状态
            launch(Dispatchers.IO) {
                val grant = PermissionManager.checkGrantType(forceRefresh = true)
                _uiState.update { it.copy(permissionGrant = grant) }
            }
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    /** 刷新权限授权状态（权限页返回后调用） */
    fun refreshPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            PermissionManager.invalidateCache()
            val grant = PermissionManager.checkGrantType(forceRefresh = true)
            _uiState.update { it.copy(permissionGrant = grant) }
        }
    }

    private fun buildState(): HomeUiState = runCatching {
        val kernelVersion = getKernelVersion()
        val isManager = Natives.isManager
        val ksuVersion = if (isManager) Natives.version else null
        val kernelUAPIVersion = if (isManager) Natives.kernelUAPIVersion else null
        val managerUAPIVersion = Natives.managerUAPIVersion
        val lkmMode = runCatching {
            ksuVersion?.let { if (kernelVersion.isGKI()) Natives.isLkmMode else null }
        }.getOrNull()
        val isRootAvailable = runCatching { rootAvailable() }.getOrDefault(false)
        val managerVersion = getManagerVersion(ksuApp)
        val currentGrant = runCatching {
            if (isRootAvailable) PermissionGrantType.ROOT else PermissionGrantType.NONE
        }.getOrDefault(PermissionGrantType.NONE)

        HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = Natives.isPrBuild,
            requiresNewKernel = runCatching { isManager && Natives.requireNewKernel() }.getOrDefault(false),
            uapiMismatch = runCatching { isManager && Natives.checkUAPIMismatch() }.getOrDefault(false),
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = Natives.isSafeMode,
            isLateLoadMode = Natives.isLateLoadMode,
            checkUpdateEnabled = runCatching { settingsRepo.checkUpdate }.getOrDefault(true),
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("unknown"),
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
                deviceModel = runCatching { resolveDeviceName() }.getOrDefault("${Build.MANUFACTURER} ${Build.MODEL}"),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = runCatching { getSELinuxStatusRaw() }.getOrDefault("Unknown"),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
            permissionGrant = currentGrant,
        )
    }.getOrElse { ex ->
        // buildState 任何异常都兜底，给出一个"能让界面显示出来"的最小状态
        val managerVersion = runCatching { getManagerVersion(ksuApp) }
        HomeUiState(
            kernelVersion = getKernelVersion(),
            ksuVersion = null,
            lkmMode = null,
            isManager = false,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = false,
            requiresNewKernel = false,
            uapiMismatch = false,
            kernelUAPIVersion = null,
            managerUAPIVersion = -1,
            isRootAvailable = false,
            isSafeMode = false,
            isLateLoadMode = false,
            checkUpdateEnabled = false,
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.getOrNull()?.versionCode ?: 0,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("unknown"),
                managerVersion = "${managerVersion.getOrNull()?.versionName ?: "0"} build-state-error",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = "Unknown",
                seccompStatus = -1,
            ),
            permissionGrant = PermissionGrantType.NONE,
            buildError = ex.localizedMessage ?: ex.javaClass.simpleName,
        )
    }
}
