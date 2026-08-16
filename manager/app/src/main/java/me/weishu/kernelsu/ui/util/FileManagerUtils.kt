package me.weishu.kernelsu.ui.util

import android.content.ComponentName
import android.os.IBinder
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 崩溃修复（ConcurrentModificationException）：
     * Shizuku 在主线程遍历内部 listener map 时，旧的"每次命令 bind → 回调里立即 unbind"
     * 会在同一次遍历中修改该 map，导致崩溃。
     * 现改为：整个应用生命周期只 bind 一次，缓存 Binder 复用，永不主动 unbind；
     * Binder 失效时清空缓存并自动重绑。Mutex 防止启动时多页面并发触发重复绑定。
     */
    private val bindMutex = Mutex()

    @Volatile
    private var cachedService: IShellService? = null

    private suspend fun execWithShizuku(cmd: String): String? {
        // 快路径：服务已绑定，直接调用
        cachedService?.let { s ->
            runCatching { return s.exec(cmd) }
            cachedService = null // Binder 已失效，走重绑
        }
        return bindMutex.withLock {
            // 双重检查：等锁期间可能已被别的协程绑定
            cachedService?.let { s ->
                runCatching { return@withLock s.exec(cmd) }
                cachedService = null
            }
            val s = bindShellService() ?: return@withLock null
            cachedService = s
            runCatching { s.exec(cmd) }.getOrNull()
        }
    }

    /**
     * 绑定 UserService（整个会话只调用一次）。
     * 注意：onServiceConnected 里不做任何 unbind / map 修改，
     * 否则会撞上 Shizuku 主线程的 listener 遍历（ConcurrentModificationException）。
     */
    private suspend fun bindShellService(): IShellService? =
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
                    cachedService = service
                    if (cont.isActive) cont.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 服务进程死亡，清缓存，下次调用自动重绑
                    cachedService = null
                }
            }

            val bound = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
            if (!bound && cont.isActive) {
                cont.resume(null)
            }
            // 不注册 invokeOnCancellation 解绑：绑定是会话级的，保持占用即可
        }
}
