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
 * （/storage/emulated/0/Android/data/<pkg>/files/luoxi_work/）。
 * 不能用 /data/data 内部 cache——Shizuku(adb) 权限访问不到，这是之前替换/备份失败的根因。
 */
object FileManagerUtils {

    /**
     * 替换游戏文件的执行结果，UI 据此给出明确提示。
     */
    enum class ReplaceResult {
        /** 替换成功 */
        SUCCESS,

        /** 文件输出目录为空（请先制作文件） */
        NO_OUTPUT_FILES,

        /** 无 Root/Shizuku 权限 */
        NO_PERMISSION,

        /** 游戏目录为空，无法备份 */
        GAME_DIR_EMPTY,

        /** 备份失败（压缩/写入备份目录失败），游戏目录未改动 */
        BACKUP_FAILED,

        /** 移动文件至游戏目录失败（已尝试从备份回滚） */
        MOVE_FAILED
    }

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

    /** 单条命令执行超时（文件操作可能较慢，30 秒） */
    private const val EXEC_TIMEOUT_MS = 30_000L

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
     * 把中转目录里的文件批量移动到目标目录（shell mv，自动建目录）。
     * 用 ls -A 先判断源目录是否有文件，再用 find -exec 逐个移动，处理特殊文件名。
     */
    private suspend fun moveFilesToDir(srcDir: String, targetDir: String): Boolean {
        if (exec("mkdir -p '$targetDir'") == null) return false
        // 先检查源目录是否有文件，防止 mv * 在空目录下通配符不展开
        val hasFiles = exec("ls -A '$srcDir' 2>/dev/null")?.trim()?.isNotEmpty() == true
        if (!hasFiles) return true // 源目录为空，无需移动
        // 用 find -exec 逐个移动，通配符不展开也能正确处理，且文件名含空格/特殊字符不受影响
        val ok = exec(
            "find '$srcDir' -maxdepth 1 -type f -exec mv {} '$targetDir'/ \\; 2>/dev/null; echo done"
        ) != null
        if (!ok) return false
        // 验证源目录已清空（exec 返回 null 表示命令失败，不能视为成功）
        val remaining = exec("ls -A '$srcDir' 2>/dev/null")?.trim() ?: return false
        return remaining.isEmpty()
    }

    /**
     * 替换游戏文件（严格按「先检查 → 先备份 → 再清空 → 最后移动」的顺序）：
     * 1. 检查文件输出目录有成品（没有就直接失败，绝不动任何东西）
     * 2. 需要备份时先备份：游戏文件 复制 → work/bak → 压缩 zip → 备份/时间戳.zip
     * 3. 清空游戏目录
     * 4. 文件输出目录内文件 移动（mv）→ 游戏目录
     * 5. 移入失败且已有备份 → 自动从备份回滚
     *
     * 关键：文件输出目录只在最后一步才被移动，中间任何一步失败都不会消费用户文件。
     */
    suspend fun replaceGameFiles(withBackup: Boolean, onStep: suspend (String) -> Unit): ReplaceResult {
        val work = workDir().absolutePath

        // 1. 先确认有成品（只读检查，不移动，避免失败时用户文件消失）
        onStep("正在读取制作好的文件")
        val outCount = exec("ls -A '$OUTPUT_DIR' 2>/dev/null | wc -l")?.trim()
            ?: return ReplaceResult.NO_PERMISSION
        if (outCount == "0") return ReplaceResult.NO_OUTPUT_FILES

        // 2. 需要备份时先备份；失败立即终止，文件输出目录原封不动
        var backupName: String? = null
        if (withBackup) {
            // 先确认游戏目录里有可备份的文件，给出明确的「空目录」提示（而不是笼统失败）
            val gameCount = exec("ls -A '$LOADING_BG_DIR' 2>/dev/null | wc -l")?.trim()
                ?: return ReplaceResult.NO_PERMISSION
            if (gameCount == "0") {
                onStep("游戏目录为空，无法备份（$LOADING_BG_DIR）")
                return ReplaceResult.GAME_DIR_EMPTY
            }
            backupName = backupGameFiles(work, onStep)
                ?: return ReplaceResult.BACKUP_FAILED
        }

        // 3. 清空游戏目录
        onStep("正在删除游戏文件")
        if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) {
            return ReplaceResult.NO_PERMISSION
        }

        // 4. 把文件输出目录的文件移动（mv）到游戏目录
        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir(OUTPUT_DIR, LOADING_BG_DIR)

        // 5. 移入失败：立即用刚生成的备份回滚
        if (!ok && backupName != null) {
            onStep("移动失败，正在从备份回滚")
            val zip = java.io.File(work, "rollback.zip")
            if (exec("cp '$BACKUP_DIR/$backupName' '${zip.absolutePath}'") != null) {
                restoreFromFile(zip, onStep)
            }
        }
        return if (ok) ReplaceResult.SUCCESS else ReplaceResult.MOVE_FAILED
    }

    /**
     * 备份游戏目录（复制式，zip 确认写入备份目录前游戏目录保持原样）：
     * 游戏目录内文件 复制 → work/bak（中转，Java 可读）→ Java 压缩 zip → work/luoxi_backup.zip → 备份/yy.MM.dd HH-mm-ss.zip
     * - 游戏目录为空 → 失败（不生成空备份）
     * - 压缩或入库任一步失败 → 清理中转即止，游戏目录不受影响（因为是复制，无需回滚）
     * @return 备份文件名；失败返回 null
     */
    private suspend fun backupGameFiles(
        work: String,
        onStep: suspend (String) -> Unit
    ): String? {
        exec("rm -rf '$work/bak'; mkdir -p '$work/bak'")
        // 复制（不是移动）：用 dir/. 方式连同隐藏文件一起拷到中转
        exec("mkdir -p '$LOADING_BG_DIR'; cp -rf '$LOADING_BG_DIR'/. '$work/bak' 2>/dev/null; echo copied")

        val bakDir = java.io.File(work, "bak")
        val files = bakDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            // 游戏目录没文件可备份，清理中转
            cleanDir(bakDir)
            onStep("读取游戏文件失败，无法备份")
            return null
        }
        onStep("正在备份游戏文件（${files.size} 个）")

        // 压缩到中转目录（Java 层直接操作，无需 shell）
        // 注意：Android 模拟存储（FUSE/sdcardfs）不允许文件名含冒号 :
        val stamp = java.text.SimpleDateFormat("yy.MM.dd HH-mm-ss", java.util.Locale.getDefault())
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
            // 游戏目录没动过，只需清中转
            onStep("压缩失败")
            cleanDir(bakDir)
            runCatching { zip.delete() }
            return null
        }

        // zip 复制到备份目录（cp 保留原件，确认成功后再删；避免 mv 失败导致 zip 丢失）
        if (exec("mkdir -p '$BACKUP_DIR'; cp '${zip.absolutePath}' '$BACKUP_DIR/$stamp.zip'") == null) {
            onStep("写入备份目录失败")
            cleanDir(bakDir)
            runCatching { zip.delete() }
            return null
        }
        val size = exec("stat -c '%s' '$BACKUP_DIR/$stamp.zip' 2>/dev/null")?.trim()
        if (size.isNullOrEmpty() || size == "0") {
            onStep("备份文件为空")
            exec("rm -f '$BACKUP_DIR/$stamp.zip'")
            cleanDir(bakDir)
            runCatching { zip.delete() }
            return null
        }
        // cp + 验证均通过，删除中转 zip
        runCatching { zip.delete() }
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

        if (!zip.exists() || zip.length() == 0L) return false

        // 改用 ZipFile（读中央目录，非流式；比 ZipInputStream 更可靠，无需手动 closeEntry）
        val count = runCatching {
            var n = 0
            java.util.zip.ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        // 防路径穿越：只取文件名
                        val name = entry.name.substringAfterLast('/').ifEmpty { entry.name }
                        java.io.File(restoreDir, name).let { f ->
                            zf.getInputStream(entry).use { input ->
                                java.io.FileOutputStream(f).use { input.copyTo(it) }
                            }
                        }
                        n++
                    }
                }
            }
            n
        }.onFailure {
            return false
        }.getOrDefault(0)

        // 空备份（0 个文件）：不动游戏目录，直接失败
        if (count == 0) return false

        // 安全策略：先把当前游戏文件移到中转目录，还原成功后再清理
        // 避免还原失败导致游戏目录被清空
        onStep("正在备份当前游戏文件（$count 个备份文件待还原）")
        val oldBackupDir = java.io.File(work, "old_bak")
        cleanDir(oldBackupDir)
        if (!oldBackupDir.exists()) oldBackupDir.mkdirs()
        val hasOldFiles = exec("ls -A '$LOADING_BG_DIR' 2>/dev/null")?.trim()?.isNotEmpty() == true
        if (hasOldFiles) {
            if (!moveFilesToDir(LOADING_BG_DIR, "$wp/old_bak")) {
                onStep("备份当前游戏文件失败，还原中止")
                cleanDir(restoreDir)
                return false
            }
        }

        onStep("正在移动文件至游戏目录")
        val ok = moveFilesToDir("$wp/restore", LOADING_BG_DIR)

        if (ok) {
            // 还原成功：清理旧备份
            cleanDir(oldBackupDir)
        } else {
            // 还原失败：把旧文件移回游戏目录
            onStep("移动失败，正在回滚游戏文件")
            if (hasOldFiles) {
                moveFilesToDir("$wp/old_bak", LOADING_BG_DIR)
            }
            cleanDir(oldBackupDir)
        }

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