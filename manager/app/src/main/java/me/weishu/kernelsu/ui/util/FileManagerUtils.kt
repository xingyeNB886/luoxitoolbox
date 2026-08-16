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

    /** 备份目录（替换游戏文件时的自动备份压缩包存放处） */
    const val BACKUP_DIR = "$LUOXI_DIR/备份"

    /** 文件输出目录（制作好的文件存放处） */
    const val OUTPUT_DIR = "$LUOXI_DIR/文件输出"

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
     * 执行初始化：创建 luoxi 目录、备份/文件输出子目录和标记文件。
     * mkdir -p / touch 天然幂等——已存在的直接跳过，缺的补上。
     * @return 是否成功（无权限返回 false）
     */
    suspend fun ensureInitFiles(): Boolean {
        val cmd = "mkdir -p '$LUOXI_DIR' '$BACKUP_DIR' '$OUTPUT_DIR'; touch '$MARK_FILE'"
        return exec(cmd) != null
    }

    /**
     * 读取伪装系统文件里记录的游戏文件名列表。
     * @return 文件名列表；无权限/文件不存在返回空列表
     */
    suspend fun readRecordedNames(): List<String> {
        return exec("cat '$MARK_FILE'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("cat:") && !it.contains("No such file") }
            ?: emptyList()
    }

    /**
     * 列出备份目录里的压缩包文件名。
     * @return 文件名列表；无权限/目录不存在返回空列表
     */
    suspend fun listBackups(): List<String> {
        return exec("ls -1 '$BACKUP_DIR'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("ls:") && !it.contains("No such file") }
            ?: emptyList()
    }

    /**
     * 列出文件输出目录里的文件名。
     */
    suspend fun listOutputFiles(): List<String> {
        return exec("ls -1 '$OUTPUT_DIR'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("ls:") && !it.contains("No such file") }
            ?: emptyList()
    }

    /**
     * 把应用 cache 里的文件批量移动到目标目录（shell mv，自动建目录）。
     * 文件较多时自动分批，避免单条命令过长。
     */
    suspend fun moveFilesToDir(files: List<java.io.File>, targetDir: String): Boolean {
        if (files.isEmpty()) return true
        if (exec("mkdir -p '$targetDir'") == null) return false
        files.chunked(40).forEach { chunk ->
            val cmd = chunk.joinToString(" ") { "'${it.absolutePath}'" }
            if (exec("mv $cmd '$targetDir'/") == null) return false
        }
        return true
    }

    /**
     * 替换游戏文件：删除游戏目录（LoadingBG）内文件，把制作好的文件移进去。
     *
     * @param withBackup true = 先把游戏目录所有文件打包备份到 备份/ 目录
     * @param onStep 实时进度回调（一次一条，主线程外调用，UI 自行切主线程）
     * @return 是否成功
     */
    suspend fun replaceGameFiles(withBackup: Boolean, onStep: suspend (String) -> Unit): Boolean {
        // 待替换的成品文件先搬进 app cache（Java 层可见），再统一移入游戏目录
        onStep("正在读取制作好的文件")
        val cacheDir = ksuApp.cacheDir
        val staged = java.io.File(cacheDir, "luoxi_out").apply { mkdirs() }
        // 清掉上次残留
        staged.listFiles()?.forEach { runCatching { it.delete() } }
        // shell mv 输出目录文件到 cache（rm 残留 + mv）
        exec("rm -rf '${staged.absolutePath}'; mkdir -p '${staged.absolutePath}'")
        exec("mv '$OUTPUT_DIR'/* '${staged.absolutePath}'/" +
                " 2>/dev/null; ls -1 '${staged.absolutePath}'") ?: return false

        val stagedFiles = staged.listFiles()?.toList() ?: emptyList()
        // 没有成品文件就失败退出，绝不动游戏目录
        if (stagedFiles.isEmpty()) return false

        if (withBackup) {
            onStep("正在备份游戏文件")
            val ok = backupGameFiles()
            if (!ok) return false
        } else {
            onStep("正在删除游戏文件")
            if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) return false
        }

        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir(stagedFiles, LOADING_BG_DIR)
        // 清理空目录
        runCatching { staged.delete() }
        return ok
    }

    /**
     * 备份游戏目录：所有文件 → cache → 压缩 zip（Java ZipOutputStream，无外部依赖）
     * → 移动到 备份/yy.MM.dd HH:mm.zip
     */
    private suspend fun backupGameFiles(): Boolean {
        val tmp = java.io.File(ksuApp.cacheDir, "luoxi_backup_tmp").apply {
            mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
        }
        // 游戏目录所有文件搬到 app cache（Java 层可读）
        exec("rm -rf '${tmp.absolutePath}'; mkdir -p '${tmp.absolutePath}'")
        exec("mkdir -p '$LOADING_BG_DIR'; mv '$LOADING_BG_DIR'/* '${tmp.absolutePath}'/ 2>/dev/null; echo done")
            ?: run {
                // 通道失败，尝试把文件移回去
                return false
            }

        val files = tmp.listFiles()?.toList() ?: emptyList()
        // 压缩（失败则回滚：文件移回游戏目录）
        val stamp = java.text.SimpleDateFormat("yy.MM.dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val zip = java.io.File(ksuApp.cacheDir, "luoxi_backup.zip")
        runCatching { zip.delete() }
        val zipped = runCatching {
            val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip))
            files.forEach { f ->
                zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                java.io.FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.close()
        }
        if (zipped.isFailure) {
            moveFilesToDir(files, LOADING_BG_DIR)
            return false
        }

        // 压缩成功，删除已移动的临时文件
        files.forEach { runCatching { it.delete() } }
        runCatching { tmp.delete() }

        // zip 放入备份目录（文件名含空格，引号包裹）
        exec("mkdir -p '$BACKUP_DIR'")
        val target = "'$BACKUP_DIR/$stamp.zip'"
        return exec("mv '${zip.absolutePath}' $target") != null
    }

    /**
     * 还原备份：解压 zip → 删除游戏目录文件 → 解压文件移入游戏目录。
     *
     * @param backupFile 备份 zip（app 可读的本地文件，自定义文件或备份目录里的）
     * @param onStep 实时进度回调
     */
    suspend fun restoreBackup(backupFile: java.io.File, onStep: suspend (String) -> Unit): Boolean {
        onStep("正在解压备份文件")
        val restoreDir = java.io.File(ksuApp.cacheDir, "luoxi_restore").apply {
            mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
        }
        exec("rm -rf '${restoreDir.absolutePath}'; mkdir -p '${restoreDir.absolutePath}'")
        runCatching {
            java.util.zip.ZipInputStream(java.io.FileInputStream(backupFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('/', '_').substringAfterLast('/')
                        val f = java.io.File(restoreDir, name)
                        java.io.FileOutputStream(f).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.onFailure { return false }
        val restored = restoreDir.listFiles()?.toList() ?: emptyList()

        onStep("正在删除游戏文件")
        if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) return false

        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir(restored, LOADING_BG_DIR)
        if (ok) {
            // 成功才清理临时文件；失败时保留 cache 副本，避免数据丢失
            restored.forEach { runCatching { it.delete() } }
            runCatching { restoreDir.delete() }
            runCatching { backupFile.delete() }
        }
        return ok
    }

    /**
     * 把备份目录里的 zip 复制到 app cache 供 Java 层解压（cp 保留备份原件）。
     * @return cache 里的 zip 文件；失败返回 null
     */
    suspend fun fetchBackupToCache(name: String): java.io.File? {
        val target = java.io.File(ksuApp.cacheDir, "luoxi_backup_pick.zip")
        runCatching { target.delete() }
        val ok = exec("cp '$BACKUP_DIR/$name' '${target.absolutePath}'") != null
        return if (ok) target else null
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
