package me.weishu.kernelsu.ui.util

import android.content.ComponentName
import android.os.IBinder
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.service.IShellService
import me.weishu.kernelsu.service.ShellService
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * 文件管理页的命令执行工具
 *
 * 通过 Root shell 或 Shizuku UserService 执行命令，
 * 用于创建/检测 /sdcard/luoxi 目录与 Android/data 下的初始化标记文件。
 */
object FileManagerUtils {

    /** 初始化目录 */
    const val LUOXI_DIR = "/storage/emulated/0/luoxi"

    /**
     * 初始化标记文件（伪装成系统缓存索引文件）
     * 位于 Android/data 根目录，普通应用无法访问，必须用 shell 权限检测
     */
    const val MARK_FILE = "/storage/emulated/0/Android/data/.media_cache_index"

    /**
     * 和平精英 LoadingBG 图片目录（统计文件个数 / 记录文件名用）
     */
    const val LOADING_BG_DIR =
        "/storage/emulated/0/Android/data/com.tencent.tmgp.pubgmhd/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/ImageDownloadV3/LoadingBG"

    /** 单条命令执行超时，防止 shell 通道卡死导致界面永久等待 */
    private const val EXEC_TIMEOUT_MS = 15_000L

    /**
     * 执行 shell 命令（自动选择 Root / Shizuku）。
     * @return 命令输出；无权限或执行失败/超时返回 null
     */
    suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        val grant = PermissionManager.checkGrantType()
        when {
            grant == PermissionGrantType.ROOT || grant == PermissionGrantType.BOTH -> {
                withTimeoutOrNull(EXEC_TIMEOUT_MS) {
                    runCatching {
                        ShellUtils.fastCmd(getRootShell(), cmd)
                    }.getOrNull()
                }
            }

            grant == PermissionGrantType.ADB -> {
                withTimeoutOrNull(EXEC_TIMEOUT_MS) { execWithShizuku(cmd) }
            }

            else -> null
        }
    }

    /**
     * 执行初始化：创建 luoxi 目录和标记文件。
     * mkdir -p / touch 天然幂等——已存在的直接跳过，缺的补上。
     * @return 是否成功（无权限返回 false）
     */
    suspend fun ensureInitFiles(): Boolean {
        val cmd = "mkdir -p '$LUOXI_DIR'; touch '$MARK_FILE'"
        return exec(cmd) != null
    }

    /**
     * 读取 LoadingBG 目录下的文件名列表。
     * @return 文件名列表（目录为空或不存在返回空列表）；无权限/失败返回 null
     */
    suspend fun listLoadingBGFiles(): List<String>? {
        val out = exec("ls -1 '$LOADING_BG_DIR'") ?: return null
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("ls:") && !it.contains("No such file") }
    }

    /**
     * 同步文件名到伪装系统文件（一行一个）：
     * 新目录里有、文件里没有的 → 追加进去；
     * 文件里有、新目录里没有的（旧的比新的多）→ 保留不动。
     */
    suspend fun syncLoadingBGFileNames(newNames: List<String>): Boolean {
        if (newNames.isEmpty()) return true
        val oldNames = exec("cat '$MARK_FILE'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("cat:") && !it.contains("No such file") }
            ?.toSet()
            ?: emptySet()
        val toAdd = newNames.filter { it !in oldNames }
        if (toAdd.isEmpty()) return true
        val cmd = toAdd.joinToString("\n") { "echo '$it' >> '$MARK_FILE'" }
        return exec(cmd) != null
    }

    // ---------- Shizuku UserService ----------

    /**
     * 通过 Shizuku UserService 执行命令。
     * bindUserService 是异步的，这里封装成 suspend。
     * 调用方须用 withTimeoutOrNull 包裹，防止服务异常时永不回调。
     */
    private suspend fun execWithShizuku(cmd: String): String? =
        suspendCancellableCoroutine { cont ->
            if (!PermissionManager.isShizukuGranted()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val args = Shizuku.UserServiceArgs(
                ComponentName(ksuApp, ShellService::class.java)
            )
                .processNameSuffix("shell")
                .version(1)

            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = IShellService.Stub.asInterface(binder)
                    val result = runCatching { service.exec(cmd) }.getOrNull()
                    // 用完即解绑，避免占用 Shizuku 服务
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }

            val bound = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
            if (!bound && cont.isActive) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
}
