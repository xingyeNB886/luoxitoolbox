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
 * 重要：所有 Java 层 ↔ shell 层的文件中转都走 app 外部私有目录
 * （/storage/emulated/0/Android/data/<pkg>/files/luoxi/）。
 * 不能用 /data/data 内部 cache——Shizuku(adb) 权限访问不到，这是之前替换/备份失败的根因。
 */
object FileManagerUtils {

    /** 初始化目录 */
    const val LUOXI_DIR = "/storage/emulated/0/luoxi"

    /** 备份目录（替换游戏文件时的自动备份压缩包存放处） */
    const val BACKUP_DIR = "$LUOXI_DIR/备份"

    /** 文件输出目录（制作好的文件存放处） */
    const val OUTPUT_DIR = "$LUOXI_DIR/文件输出"

    /** 裁剪图片目录（裁剪结果存放处） */
    const val CROP_DIR = "$LUOXI_DIR/裁剪"

    /** 初始化标记文件（伪装成系统缓存索引文件） */
    const val MARK_FILE = "/storage/emulated/0/Android/data/.media_cache_index"

    /** 和平精英 LoadingBG 图片目录 */
    const val LOADING_BG_DIR =
        "/storage/emulated/0/Android/data/com.tencent.tmgp.pubgmhd/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/ImageDownloadV3/LoadingBG"

    /** 单条命令执行超时，防止 shell 通道卡死导致界面永久等待 */
    private const val EXEC_TIMEOUT_MS = 15_000L

    /**
     * Java ↔ shell 中转工作目录（app 外部私有目录，双方都能全权访问）。
     * 首次调用时确保存在。
     */
    fun workDir(): java.io.File {
        val dir = java.io.File(ksuApp.getExternalFilesDir(null), "luoxi_work")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 清空目录内所有文件（目录保留） */
    private fun cleanDir(dir: java.io.File) {
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

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

    /** 执行初始化：创建 luoxi 目录、备份/文件输出/裁剪子目录和标记文件（幂等） */
    suspend fun ensureInitFiles(): Boolean {
        val cmd = "mkdir -p '$LUOXI_DIR' '$BACKUP_DIR' '$OUTPUT_DIR' '$CROP_DIR'; touch '$MARK_FILE'"
        return exec(cmd) != null
    }

    /** 读取伪装系统文件里记录的游戏文件名列表 */
    suspend fun readRecordedNames(): List<String> {
        return exec("cat '$MARK_FILE'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("cat:") && !it.contains("No such file") }
            ?: emptyList()
    }

    /** 列出备份目录里的压缩包文件名 */
    suspend fun listBackups(): List<String> {
        return exec("ls -1 '$BACKUP_DIR'")
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("ls:") && !it.contains("No such file") }
            ?: emptyList()
    }

    /**
     * 清理缓存：清空「文件输出」与「裁剪」目录（目录保留）。
     * @return 是否成功（无权限返回 false）
     */
    suspend fun clearCacheDirs(): Boolean {
        return exec("rm -rf '$OUTPUT_DIR' '$CROP_DIR'; mkdir -p '$OUTPUT_DIR' '$CROP_DIR'") != null
    }

    /**
     * 把裁剪结果文件复制到 luoxi/裁剪/（目录不存在时自动创建）。
     * @return 是否成功
     */
    suspend fun publishCropFile(src: java.io.File): Boolean {
        if (exec("mkdir -p '$CROP_DIR'") == null) return false
        return exec("cp '${src.absolutePath}' '$CROP_DIR/${src.name}'") != null
    }

    /**
     * 把中转目录里的文件批量移动到目标目录（shell mv，自动建目录，分批防命令过长）。
     */
    private suspend fun moveFilesToDir(srcDir: String, targetDir: String): Boolean {
        if (exec("mkdir -p '$targetDir'") == null) return false
        // mv 整个目录内容（包括无扩展名/特殊字符文件名，引号由 shell 通配符处理）
        return exec("mv '$srcDir'/* '$targetDir'/ 2>/dev/null; " +
                "ls -A '$srcDir' 2>/dev/null | wc -l")?.trim() == "0"
    }

    /**
     * 替换游戏文件：
     * 文件输出/ → work/out（中转）→ [备份游戏文件] → 删游戏文件 → work/out 全部文件 → 游戏目录
     * 移入失败且已有备份时自动从备份回滚，避免游戏目录被清空。
     */
    suspend fun replaceGameFiles(withBackup: Boolean, onStep: suspend (String) -> Unit): Boolean {
        val work = workDir().absolutePath

        onStep("正在读取制作好的文件")
        // 中转目录清空，把文件输出目录的内容移进来
        exec("rm -rf '$work/out'; mkdir -p '$work/out'")
        exec("mkdir -p '$OUTPUT_DIR'; mv '$OUTPUT_DIR'/* '$work/out' 2>/dev/null; echo moved")
        val outCount = exec("ls -A '$work/out' 2>/dev/null | wc -l")?.trim() ?: return false
        if (outCount == "0") return false // 没有成品文件，绝不动游戏目录

        var backupName: String? = null
        if (withBackup) {
            backupName = backupGameFiles(work, onStep) ?: return false
        } else {
            onStep("正在删除游戏文件")
            if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) return false
        }

        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir("$work/out", LOADING_BG_DIR)
        exec("rm -rf '$work/out'")

        if (!ok && backupName != null) {
            // 移入游戏目录失败：立即用刚生成的备份回滚
            onStep("移动失败，正在从备份回滚")
            val zip = java.io.File(work, "rollback.zip")
            if (exec("cp '$BACKUP_DIR/$backupName' '${zip.absolutePath}'") != null) {
                restoreFromFile(zip, onStep)
            }
        }
        return ok
    }

    /**
     * 备份游戏目录：
     * 游戏目录内文件 → work/bak（中转，Java 可读）→ Java 压缩 zip → work/xx.zip → 备份/yy.MM.dd HH:mm:ss.zip
     * - 游戏目录为空 → 失败（不生成空备份）
     * - zip 确认写入备份目录后才清理中转，任一步失败文件原路移回游戏目录
     * @return 备份文件名；失败返回 null
     */
    private suspend fun backupGameFiles(
        work: String,
        onStep: suspend (String) -> Unit
    ): String? {
        exec("rm -rf '$work/bak'; mkdir -p '$work/bak'")
        exec("mkdir -p '$LOADING_BG_DIR'; mv '$LOADING_BG_DIR'/* '$work/bak' 2>/dev/null; echo moved")

        val bakDir = java.io.File(work, "bak")
        val files = bakDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            // 游戏目录为空：不生成空备份误导还原，直接失败
            onStep("游戏目录为空，无法备份")
            return null
        }
        onStep("正在备份游戏文件（${files.size} 个）")

        // 压缩到中转目录（Java 层直接操作，无需 shell）
        val stamp = java.text.SimpleDateFormat("yy.MM.dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val zip = java.io.File(work, "luoxi_backup.zip")
        runCatching { zip.delete() }
        val zipped = runCatching {
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
                files.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    java.io.FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.isSuccess && zip.length() > 0

        if (!zipped) {
            // 回滚：文件移回游戏目录
            moveFilesToDir("$work/bak", LOADING_BG_DIR)
            return null
        }

        // zip 移入备份目录（文件名含空格，引号包裹）；成功并确认非空后才清理中转
        if (exec("mkdir -p '$BACKUP_DIR'; mv '${zip.absolutePath}' '$BACKUP_DIR/$stamp.zip'") == null) {
            moveFilesToDir("$work/bak", LOADING_BG_DIR)
            runCatching { zip.delete() }
            return null
        }
        val size = exec("stat -c '%s' '$BACKUP_DIR/$stamp.zip' 2>/dev/null")?.trim()
        if (size.isNullOrEmpty() || size == "0") {
            moveFilesToDir("$work/bak", LOADING_BG_DIR)
            return null
        }
        cleanDir(bakDir)
        return "$stamp.zip"
    }

    /**
     * 还原备份（备份目录里的 zip）：
     * 备份/xx.zip → work/pick.zip（cp 保留原件）→ 通用还原流程
     */
    suspend fun restoreBackup(backupName: String, onStep: suspend (String) -> Unit): Boolean {
        val work = workDir()
        val zip = java.io.File(work, "pick.zip")
        runCatching { zip.delete() }
        onStep("正在解压备份文件")
        if (exec("cp '$BACKUP_DIR/$backupName' '${zip.absolutePath}'") == null) return false
        return restoreFromFile(zip, onStep)
    }

    /**
     * 还原自定义备份文件（app 可读的本地 zip）：
     * 解压到 work/restore → 删游戏文件 → work/restore 内文件 → 游戏目录
     */
    suspend fun restoreFromCustomFile(zipFile: java.io.File, onStep: suspend (String) -> Unit): Boolean {
        onStep("正在解压备份文件")
        return restoreFromFile(zipFile, onStep)
    }

    private suspend fun restoreFromFile(zip: java.io.File, onStep: suspend (String) -> Unit): Boolean {
        val work = workDir()
        val wp = work.absolutePath

        val restoreDir = java.io.File(work, "restore")
        cleanDir(restoreDir)
        if (!restoreDir.exists()) restoreDir.mkdirs()
        runCatching {
            java.util.zip.ZipInputStream(java.io.FileInputStream(zip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 防路径穿越：只取文件名
                        val name = entry.name.replace('/', '_').substringAfterLast('/')
                        java.io.File(restoreDir, name).let { f ->
                            java.io.FileOutputStream(f).use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.onFailure { return false }

        // 空备份（0 个文件）：不动游戏目录，直接失败
        val count = restoreDir.listFiles()?.size ?: 0
        if (count == 0) return false

        onStep("正在删除游戏文件（$count 个备份文件将还原）")
        if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) return false

        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir("$wp/restore", LOADING_BG_DIR)
        // 无论成败都清中转（备份原件仍在备份目录，不会丢数据）
        cleanDir(restoreDir)
        runCatching { zip.delete() }
        return ok
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
     * 同步文件名到伪装系统文件（一行一个）：只增不减。
     */
    suspend fun syncLoadingBGFileNames(newNames: List<String>): Boolean {
        if (newNames.isEmpty()) return true
        val oldNames = readRecordedNames().toSet()
        val toAdd = newNames.filter { it !in oldNames }
        if (toAdd.isEmpty()) return true
        val cmd = toAdd.joinToString("\n") { "echo '$it' >> '$MARK_FILE'" }
        return exec(cmd) != null
    }

    /**
     * 把制作好的文件（中转目录内）移入文件输出目录（先清空旧输出）。
     */
    suspend fun publishToOutput(stagingDir: java.io.File): Boolean {
        val wp = stagingDir.absolutePath
        if (exec("rm -rf '$OUTPUT_DIR'; mkdir -p '$OUTPUT_DIR'") == null) return false
        return moveFilesToDir(wp, OUTPUT_DIR)
    }

    // ---------- Shizuku UserService ----------

    /**
     * 会话级单次绑定 + Binder 缓存复用（修复 ConcurrentModificationException）。
     */
    private val bindMutex = Mutex()

    @Volatile
    private var cachedService: IShellService? = null

    private suspend fun execWithShizuku(cmd: String): String? {
        cachedService?.let { s ->
            runCatching { return s.exec(cmd) }
            cachedService = null
        }
        return bindMutex.withLock {
            cachedService?.let { s ->
                runCatching { return@withLock s.exec(cmd) }
                cachedService = null
            }
            val s = bindShellService() ?: return@withLock null
            cachedService = s
            runCatching { s.exec(cmd) }.getOrNull()
        }
    }

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
                    cachedService = null
                }
            }

            val bound = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
            if (!bound && cont.isActive) {
                cont.resume(null)
            }
        }
}
