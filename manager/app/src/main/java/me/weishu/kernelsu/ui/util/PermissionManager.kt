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
 *  2. Shizuku 授权检测：优先 SDK 自带接口，同时"实际执行命令"方式用反射调用
 *     Shizuku 私有方法 newProcess(String[],String[],File) 做兜底验证。
 */
object PermissionManager {

    private var cachedGrantType: PermissionGrantType? = null

    /** 检测 Root 权限是否可用（基于 libsu） */
    fun isRootGranted(): Boolean {
        return runCatching { rootAvailable() }.getOrDefault(false)
    }

    /**
     * 检测 Shizuku 是否真的可用（已授权 + 能执行 shell 命令）。
     * 不再依赖 checkSelfPermission 做唯一依据，因为没声明 ShizukuProvider 时
     * 它的返回值不稳定（尤其是 Shizuku 11+ 的新模型）。
     */
    fun isShizukuGranted(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            // 旧版 Shizuku（<11）：PreV11 前不需要显式授权，binder 通就算通
            if (Shizuku.isPreV11()) return true
            // 新版 Shizuku：优先 SDK 自带权限检测
            val sdkGranted = runCatching {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (sdkGranted) return true
            // 兜底：反射调用 Shizuku.newProcess 执行 id -u
            canExecuteShellIdViaShizuku()
        }.getOrDefault(false)
    }

    /**
     * 反射调用 rikka.shizuku.Shizuku#newProcess(cmd, env, dir)：
     *   public static Process newProcess(String[] cmd, String[] env, File dir)
     * 如果能成功执行且 stdout 返回 "2000" 或 "0"，说明 Shizuku 确实授权了。
     */
    private fun canExecuteShellIdViaShizuku(): Boolean {
        return runCatching {
            val shizukuClass = Shizuku::class.java
            val newProcessMethod = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                java.io.File::class.java
            )
            @Suppress("UNCHECKED_CAST")
            val process = newProcessMethod.invoke(
                null,
                arrayOf("id", "-u"),
                null,
                null
            ) as? Process ?: return false
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val uidStr = reader.readLine()?.trim()
            val uid = uidStr?.toIntOrNull()
            runCatching { reader.close() }
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
            // shell uid = 2000, root uid = 0
            uid == 2000 || uid == 0
        }.getOrDefault(false)
    }

    /**
     * 请求 Shizuku 授权。没 ShizukuProvider 时 requestPermission 可能不弹，
     * 但仍尝试调用，用户也可手动在 Shizuku App 中授权后点刷新按钮重测。
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
