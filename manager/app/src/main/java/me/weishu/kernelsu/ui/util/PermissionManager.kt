package me.weishu.kernelsu.ui.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import rikka.shizuku.Shizuku

/**
 * 权限授权类型
 */
enum class PermissionGrantType {
    /** 未授权 */
    NONE,
    /** Shizuku (adb shell) 授权 */
    ADB,
    /** Root 授权 */
    ROOT,
    /** 两者都已授权（优先显示 ROOT） */
    BOTH;

    val isWorking: Boolean get() = this != NONE

    /** 用于首页状态卡尖括号标签的显示文字 */
    val displayLabel: String
        get() = when (this) {
            ADB -> "adb"
            ROOT -> "root"
            BOTH -> "root"
            NONE -> ""
        }
}

/**
 * 权限检测管理器
 */
object PermissionManager {

    private var cachedGrantType: PermissionGrantType? = null

    /** 检测 Root 权限是否可用（基于 libsu） */
    fun isRootGranted(): Boolean {
        return runCatching { rootAvailable() }.getOrDefault(false)
    }

    /** 检测 Shizuku 是否授权（Shizuku binder 可用 + 权限已授予） */
    fun isShizukuGranted(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && (
                Shizuku.isPreV11() ||
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权（调用后会弹出 Shizuku 授权对话框）
     * @param requestCode 请求码
     * @return true=已授权，false=需要用户确认
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(requestCode)
            false
        }.getOrDefault(false)
    }

    /** 检查当前授权类型，不走缓存 */
    suspend fun checkGrantType(forceRefresh: Boolean = false): PermissionGrantType =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) cachedGrantType?.let { return@withContext it }
            val root = isRootGranted()
            val shizuku = isShizukuGranted()
            val type = when {
                root && shizuku -> PermissionGrantType.BOTH
                root -> PermissionGrantType.ROOT
                shizuku -> PermissionGrantType.ADB
                else -> PermissionGrantType.NONE
            }
            cachedGrantType = type
            type
        }

    /** 清除缓存（授权页操作后调用） */
    fun invalidateCache() {
        cachedGrantType = null
    }

    /**
     * 使用 Shizuku 执行特权命令（仅当 Shizuku 已授权时可用）
     */
    fun <T> withShizukuContext(block: () -> T): Result<T> {
        return runCatching {
            if (!isShizukuGranted()) error("Shizuku not granted")
            block()
        }
    }

    /** 检查目标包名的 Shizuku Provider 上下文 */
    fun getShizukuUserServiceArgs(context: Context = ksuApp): Shizuku.UserServiceArgs? {
        return null
    }
}
