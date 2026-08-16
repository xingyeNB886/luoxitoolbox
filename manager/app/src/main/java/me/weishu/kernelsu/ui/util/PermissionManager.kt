package me.weishu.kernelsu.ui.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

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
 *
 * 洛茜工具箱改造说明：
 *  1. 不再依赖 ShizukuProvider（Manifest 未声明）— 避免 Provider 类加载阶段
 *     native SIGABRT 直接杀进程，连崩溃日志都没有。
 *  2. Shizuku 授权检测改用"实际执行 id 命令"方式验证，只要能拿到 uid=2000(shell)
 *     即视为授权成功；checkSelfPermission / requestPermission 仅作为最佳努力。
 */
object PermissionManager {

    private var cachedGrantType: PermissionGrantType? = null

    /** 检测 Root 权限是否可用（基于 libsu） */
    fun isRootGranted(): Boolean {
        return runCatching { rootAvailable() }.getOrDefault(false)
    }

    /**
     * 检测 Shizuku 是否真的可用（已授权 + 能执行 shell 命令）。
     * 不依赖 checkSelfPermission，因为没声明 ShizukuProvider 时它返回值不稳定。
     */
    fun isShizukuGranted(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            // 新 Shizuku（API 30+）：PreV11 前不需要显式授权
            if (Shizuku.isPreV11()) return true
            // 最佳努力：先试 SDK 自带权限检测
            val sdkGranted = runCatching {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (sdkGranted) return true
            // 兜底：实际跑一条命令，能拿到 shell uid 就算授权
            canExecuteShellIdViaShizuku()
        }.getOrDefault(false)
    }

    /**
     * 尝试通过 Shizuku UserService / newProcess 方式执行 "id"，
     * 输出里含 "uid=2000" 或 "uid=0" 就认为有权限。
     */
    private fun canExecuteShellIdViaShizuku(): Boolean {
        return runCatching {
            // 方案：反射 Shizuku.newProcess，和普通 shell 一样执行命令
            val process = Shizuku.newProcess(arrayOf("id", "-u"), null, null) ?: return false
            val exitCode = runCatching {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val uid = reader.readLine()?.trim()?.toIntOrNull()
                reader.close()
                process.waitFor()
                process.destroy()
                uid
            }.getOrDefault(null)
            // shell uid=2000, root uid=0
            exitCode != null && (exitCode == 2000 || exitCode == 0)
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权。
     * 没 ShizukuProvider 时 requestPermission 可能失败，用户需要自己去 Shizuku App 里授权。
     * 但我们仍然尝试调用 SDK 接口 —— 能弹就弹。
     * @return true=已经授权，false=需要用户在 Shizuku App 里或即将弹出的对话框授权
     */
    fun requestShizukuPermission(requestCode: Int = 10001): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true
            if (isShizukuGranted()) return true
            runCatching { Shizuku.requestPermission(requestCode) }.getOrDefault(Unit)
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

    /** 检查目标包名的 Shizuku Provider 上下文（洛茜工具箱不用，保留空实现） */
    fun getShizukuUserServiceArgs(context: Context = ksuApp): Shizuku.UserServiceArgs? {
        return null
    }
}
