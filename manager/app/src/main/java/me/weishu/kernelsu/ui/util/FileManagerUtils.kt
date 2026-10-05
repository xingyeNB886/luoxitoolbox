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
 * 文件管理 / 安全格机 的文件与命令工具。
 *
 * 目录分工：
 *  - 数据目录（备份/文件输出/裁剪/标记文件/安全格机脚本）全部在 **应用私有目录**
 *    /data/data/<pkg>/files/…，由 App 端（Java）直接读写，无需 shell 权限。
 *  - 需要提权的操作（读写游戏目录）由 shell（root / Shizuku-ADB）完成。
 *  - App 与 shell 之间的二进制中转放在 **App 外部私有目录**
 *    /storage/emulated/0/Android/data/<pkg>/files/luoxi_work（双方都能访问）。
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

    /**
     * 独立备份游戏文件的执行结果。
     */
    enum class BackupResult {
        /** 备份成功 */
        SUCCESS,

        /** 无 Root/Shizuku 权限 */
        NO_PERMISSION,

        /** 游戏目录为空，无法备份 */
        GAME_DIR_EMPTY,

        /** 备份失败（复制/压缩/写入备份目录失败） */
        BACKUP_FAILED
    }

    // ---------- 应用私有目录（App 端直接读写） ----------

    /** 私有数据根目录 */
    private val dataRoot: java.io.File get() = java.io.File(ksuApp.filesDir, "luoxi")

    /** 备份目录 */
    val BACKUP_DIR: java.io.File get() = java.io.File(dataRoot, "备份")

    /** 文件输出目录（制作好的文件存放处） */
    val OUTPUT_DIR: java.io.File get() = java.io.File(dataRoot, "文件输出")

    /** 裁剪图片目录 */
    val CROP_DIR: java.io.File get() = java.io.File(dataRoot, "裁剪")

    /** 标记文件（记录游戏文件名与使用次数） */
    private val MARK_FILE: java.io.File get() = java.io.File(dataRoot, ".cache_index")

    /** 安全格机脚本目录（<内部版本号>.sh） */
    val SAFE_FORMAT_DIR: java.io.File get() = java.io.File(ksuApp.filesDir, "safe_format")

    // ---------- 外部路径 ----------

    /** 导出备份的正常目录（导出时把私有目录的备份复制到这里） */
    const val EXPORT_DIR = "/storage/emulated/0/luoxi/备份"

    /** 和平精英 LoadingBG 图片目录 */
    const val LOADING_BG_DIR =
        "/storage/emulated/0/Android/data/com.tencent.tmgp.pubgmhd/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/ImageDownloadV3/LoadingBG"

    /** 首次使用时间戳在标记文件里的行前缀（伪装成索引数据，读取文件名时过滤掉） */
    const val FIRST_USE_TAG = "LUOXI_FIRST_USE="

    /** 使用次数在标记文件里的行前缀（伪装成索引数据，读取文件名时过滤掉） */
    const val USE_COUNT_TAG = "LUOXI_USE_COUNT="

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

    // ---------- shell 执行（root / Shizuku-ADB） ----------

    suspend fun exec(cmd: String, timeoutMs: Long = EXEC_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        val grant = PermissionManager.checkGrantType()
        when {
            grant == PermissionGrantType.ROOT || grant == PermissionGrantType.BOTH -> {
                withTimeoutOrNull(timeoutMs) {
                    runCatching {
                        ShellUtils.fastCmd(getRootShell(), cmd)
                    }.getOrNull()
                }
            }

            grant == PermissionGrantType.ADB -> {
                withTimeoutOrNull(timeoutMs) { execWithShizuku(cmd) }
            }

            else -> null
        }
    }

    // ---------- 私有目录初始化（App 端，幂等，自动调用） ----------

    /** 创建私有目录结构（备份/文件输出/裁剪/安全格机 + 标记文件）。App 端直接创建，无需权限。 */
    suspend fun ensureInitFiles(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dataRoot.mkdirs()
            BACKUP_DIR.mkdirs()
            OUTPUT_DIR.mkdirs()
            CROP_DIR.mkdirs()
            SAFE_FORMAT_DIR.mkdirs()
            if (!MARK_FILE.exists()) MARK_FILE.createNewFile()
            workDir()
            true
        }.getOrDefault(false)
    }

    // ---------- 标记文件（App 端读写） ----------

    /** 读取标记文件里记录的游戏文件名列表（排除时间戳/计数行） */
    suspend fun readRecordedNames(): List<String> = withContext(Dispatchers.IO) {
        if (!MARK_FILE.exists()) return@withContext emptyList()
        runCatching { MARK_FILE.readLines() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith(FIRST_USE_TAG) && !it.startsWith(USE_COUNT_TAG)
            }
    }

    /** 读取使用次数。未记录返回 0。 */
    suspend fun readUseCount(): Int = withContext(Dispatchers.IO) {
        if (!MARK_FILE.exists()) return@withContext 0
        runCatching {
            MARK_FILE.readLines()
                .firstOrNull { it.trim().startsWith(USE_COUNT_TAG) }
                ?.substringAfter('=')
                ?.trim()
                ?.toIntOrNull()
        }.getOrNull() ?: 0
    }

    /**
     * 自增使用次数（覆盖旧值）。
     * @return 自增后的次数（失败返回旧值）
     */
    suspend fun incrementUseCount(): Int = withContext(Dispatchers.IO) {
        val current = readUseCount()
        val next = current + 1
        runCatching {
            val kept = if (MARK_FILE.exists()) {
                MARK_FILE.readLines().filter {
                    it.trim().isNotEmpty() && !it.trim().startsWith(USE_COUNT_TAG)
                }
            } else {
                emptyList()
            }
            dataRoot.mkdirs()
            MARK_FILE.writeText((kept + "$USE_COUNT_TAG$next").joinToString("\n") + "\n")
        }
        next
    }

    /** 同步文件名到标记文件（一行一个）：只增不减。 */
    suspend fun syncLoadingBGFileNames(newNames: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (newNames.isEmpty()) return@withContext true
        val oldNames = readRecordedNames().toSet()
        val toAdd = newNames.filter { it !in oldNames }
        if (toAdd.isEmpty()) return@withContext true
        runCatching {
            dataRoot.mkdirs()
            if (!MARK_FILE.exists()) MARK_FILE.createNewFile()
            MARK_FILE.appendText(toAdd.joinToString("\n") + "\n")
            true
        }.getOrDefault(false)
    }

    // ---------- 备份 / 文件输出 / 裁剪（App 端） ----------

    /** 列出备份目录里的压缩包文件名 */
    suspend fun listBackups(): List<String> = withContext(Dispatchers.IO) {
        BACKUP_DIR.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /** 清空「文件输出」与「裁剪」目录（目录保留）。 */
    suspend fun clearCacheDirs(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            cleanDir(OUTPUT_DIR)
            cleanDir(CROP_DIR)
            OUTPUT_DIR.mkdirs()
            CROP_DIR.mkdirs()
            true
        }.getOrDefault(false)
    }

    /** 把裁剪结果文件复制到 裁剪 目录。 */
    suspend fun publishCropFile(src: java.io.File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            CROP_DIR.mkdirs()
            src.copyTo(java.io.File(CROP_DIR, src.name), overwrite = true)
            true
        }.getOrDefault(false)
    }

    /** 把制作好的文件（中转目录内）移入 文件输出 目录（先清空旧输出）。 */
    suspend fun publishToOutput(stagingDir: java.io.File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            cleanDir(OUTPUT_DIR)
            OUTPUT_DIR.mkdirs()
            stagingDir.listFiles()?.forEach { f ->
                f.copyTo(java.io.File(OUTPUT_DIR, f.name), overwrite = true)
            }
            true
        }.getOrDefault(false)
    }

    // ---------- 游戏目录操作（shell + 外部私有中转） ----------

    /** 把 bridge 目录内的文件移动到游戏目录（shell mv），并校验 bridge 已清空。 */
    private suspend fun moveBridgeToGameDir(bridgeDir: java.io.File, gameDir: String): Boolean {
        if (exec("mkdir -p '$gameDir'") == null) return false
        val hasFiles = bridgeDir.listFiles()?.any { it.isFile } == true
        if (!hasFiles) return true
        val out = exec(
            "find '${bridgeDir.absolutePath}' -maxdepth 1 -type f -exec mv {} '$gameDir'/ \\; 2>/dev/null; echo done"
        )
        if (out == null) return false
        return bridgeDir.listFiles()?.none { it.isFile } == true
    }

    /** 把游戏目录内的文件移动到 bridge 目录（shell mv）。 */
    private suspend fun moveGameDirToBridge(gameDir: String, bridgeDir: java.io.File): Boolean {
        bridgeDir.mkdirs()
        val out = exec(
            "find '$gameDir' -maxdepth 1 -type f -exec mv {} '${bridgeDir.absolutePath}'/ \\; 2>/dev/null; echo done"
        )
        return out != null
    }

    /** 读取 LoadingBG 目录下的文件名列表。无权限/失败返回 null。 */
    suspend fun listLoadingBGFiles(): List<String>? {
        val out = exec("ls -1 '$LOADING_BG_DIR'") ?: return null
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("ls:") && !it.contains("No such file") }
    }

    /**
     * 替换游戏文件（先检查 → 先备份 → 再清空 → 最后移动）：
     * 1. 检查文件输出目录有成品
     * 2. 需要备份时先备份
     * 3. 成品复制到中转目录（App 端）
     * 4. 清空游戏目录（shell）
     * 5. 中转目录内文件移动（mv）到游戏目录（shell）
     * 6. 移入失败且已有备份 → 自动从备份回滚
     */
    suspend fun replaceGameFiles(withBackup: Boolean, onStep: suspend (String) -> Unit): ReplaceResult =
        withContext(Dispatchers.IO) {
            // 1. 先确认有成品
            onStep("正在读取制作好的文件")
            val outs = OUTPUT_DIR.listFiles()?.filter { it.isFile }.orEmpty()
            if (outs.isEmpty()) return@withContext ReplaceResult.NO_OUTPUT_FILES

            // 2. 需要备份时先备份
            var backupName: String? = null
            if (withBackup) {
                val gameCount = exec("ls -A '$LOADING_BG_DIR' 2>/dev/null | wc -l")?.trim()
                    ?: return@withContext ReplaceResult.NO_PERMISSION
                if (gameCount == "0") {
                    onStep("游戏目录为空，无法备份（$LOADING_BG_DIR）")
                    return@withContext ReplaceResult.GAME_DIR_EMPTY
                }
                backupName = backupGameFilesInternal(onStep)
                    ?: return@withContext ReplaceResult.BACKUP_FAILED
            }

            // 3. 成品复制到中转目录
            val stage = java.io.File(workDir(), "out").apply { deleteRecursively(); mkdirs() }
            val copied = runCatching {
                outs.forEach { it.copyTo(java.io.File(stage, it.name), overwrite = true) }
                true
            }.getOrDefault(false)
            if (!copied) return@withContext ReplaceResult.MOVE_FAILED

            // 4. 清空游戏目录
            onStep("正在删除游戏文件")
            if (exec("rm -rf '$LOADING_BG_DIR'; mkdir -p '$LOADING_BG_DIR'") == null) {
                return@withContext ReplaceResult.NO_PERMISSION
            }

            // 5. 移动至游戏目录
            onStep("正在移动文件至游戏目录")
            val ok = moveBridgeToGameDir(stage, LOADING_BG_DIR)

            // 6. 失败回滚
            if (!ok && backupName != null) {
                onStep("移动失败，正在从备份回滚")
                restoreBackup(backupName, onStep)
            }
            stage.deleteRecursively()
            if (ok) ReplaceResult.SUCCESS else ReplaceResult.MOVE_FAILED
        }

    /**
     * 备份游戏目录内部实现（复制式）：
     * 游戏目录内文件 复制 → work/bak（中转）→ App 压缩 zip → 备份/yy.MM.dd HH-mm-ss.zip
     * @return 备份文件名；失败返回 null
     */
    private suspend fun backupGameFilesInternal(onStep: suspend (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val bak = java.io.File(workDir(), "bak").apply { deleteRecursively(); mkdirs() }
            exec("mkdir -p '$LOADING_BG_DIR'; cp -rf '$LOADING_BG_DIR'/. '${bak.absolutePath}' 2>/dev/null; echo copied")

            val files = bak.listFiles()?.filter { it.isFile }.orEmpty()
            if (files.isEmpty()) {
                cleanDir(bak)
                onStep("读取游戏文件失败，无法备份")
                return@withContext null
            }
            onStep("正在备份游戏文件（${files.size} 个）")

            val stamp = java.text.SimpleDateFormat("yy.MM.dd HH-mm-ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            BACKUP_DIR.mkdirs()
            val zip = java.io.File(BACKUP_DIR, "$stamp.zip")
            runCatching { zip.delete() }
            val zipped = runCatching {
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
                    files.forEach { f ->
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        java.io.FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                zip.length() > 0
            }.getOrDefault(false)

            cleanDir(bak)
            if (!zipped) {
                runCatching { zip.delete() }
                onStep("压缩失败")
                return@withContext null
            }
            "$stamp.zip"
        }

    /**
     * 独立备份游戏文件（备份还原页「备份」入口）。
     */
    suspend fun backupGameFiles(onStep: suspend (String) -> Unit): BackupResult = withContext(Dispatchers.IO) {
        val gameCount = exec("ls -A '$LOADING_BG_DIR' 2>/dev/null | wc -l")?.trim()
            ?: return@withContext BackupResult.NO_PERMISSION
        if (gameCount == "0") {
            onStep("游戏目录为空，无法备份（$LOADING_BG_DIR）")
            return@withContext BackupResult.GAME_DIR_EMPTY
        }
        val name = backupGameFilesInternal(onStep)
        if (name != null) BackupResult.SUCCESS else BackupResult.BACKUP_FAILED
    }

    /** 还原备份（备份目录里的 zip）。 */
    suspend fun restoreBackup(backupName: String, onStep: suspend (String) -> Unit): Boolean {
        val zip = java.io.File(BACKUP_DIR, backupName)
        if (!zip.exists() || zip.length() == 0L) return false
        return restoreFromFile(zip, onStep)
    }

    /** 还原自定义备份文件（app 可读的本地 zip）。 */
    suspend fun restoreFromCustomFile(zipFile: java.io.File, onStep: suspend (String) -> Unit): Boolean {
        return restoreFromFile(zipFile, onStep)
    }

    /**
     * 通用还原：解压到 work/restore → 把当前游戏文件移到 work/old_bak → work/restore 内文件 → 游戏目录。
     * 不删除源 zip（备份原件保留）。
     */
    private suspend fun restoreFromFile(zip: java.io.File, onStep: suspend (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val restoreDir = java.io.File(workDir(), "restore").apply { deleteRecursively(); mkdirs() }
            if (!zip.exists() || zip.length() == 0L) return@withContext false

            val count = runCatching {
                var n = 0
                java.util.zip.ZipFile(zip).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory) {
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
            }.getOrElse { return@withContext false }

            if (count == 0) {
                cleanDir(restoreDir)
                return@withContext false
            }

            // 先把当前游戏文件移到中转，避免还原失败清空游戏目录
            onStep("正在备份当前游戏文件（$count 个备份文件待还原）")
            val oldBak = java.io.File(workDir(), "old_bak").apply { deleteRecursively(); mkdirs() }
            val hasOldFiles = exec("ls -A '$LOADING_BG_DIR' 2>/dev/null")?.trim()?.isNotEmpty() == true
            if (hasOldFiles) {
                if (!moveGameDirToBridge(LOADING_BG_DIR, oldBak)) {
                    onStep("备份当前游戏文件失败，还原中止")
                    cleanDir(restoreDir)
                    return@withContext false
                }
            }

            onStep("正在移动文件至游戏目录")
            val ok = moveBridgeToGameDir(restoreDir, LOADING_BG_DIR)

            if (ok) {
                cleanDir(oldBak)
            } else {
                onStep("移动失败，正在回滚游戏文件")
                if (hasOldFiles) moveBridgeToGameDir(oldBak, LOADING_BG_DIR)
                cleanDir(oldBak)
            }

            cleanDir(restoreDir)
            ok
        }

    /**
     * 导出备份：把私有目录里的备份 zip 复制到正常目录（EXPORT_DIR）。
     */
    suspend fun exportBackups(onStep: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val files = BACKUP_DIR.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            onStep("没有可导出的备份")
            return@withContext false
        }
        onStep("正在导出 ${files.size} 个备份")
        val exp = java.io.File(workDir(), "export").apply { deleteRecursively(); mkdirs() }
        val copied = runCatching {
            files.forEach { it.copyTo(java.io.File(exp, it.name), overwrite = true) }
            true
        }.getOrDefault(false)
        if (!copied) {
            cleanDir(exp)
            return@withContext false
        }
        if (exec("mkdir -p '$EXPORT_DIR'") == null) {
            cleanDir(exp)
            return@withContext false
        }
        val ok = exec(
            "find '${exp.absolutePath}' -maxdepth 1 -type f -exec cp {} '$EXPORT_DIR'/ \\; 2>/dev/null; echo done"
        ) != null
        cleanDir(exp)
        ok
    }

    // ---------- 安全格机 ----------

    /** 列出已下载的安全格机脚本版本（文件名 <内部版本号>.sh，且内容有效），降序。 */
    suspend fun listSafeFormatVersions(): List<Int> = withContext(Dispatchers.IO) {
        SAFE_FORMAT_DIR.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".sh") && isValidScriptFile(it) }
            ?.mapNotNull { it.name.removeSuffix(".sh").toIntOrNull() }
            ?.sortedDescending()
            ?: emptyList()
    }

    /** 校验下载文件是否有效（排除 HTML 反爬页等）：首字节不是 '<' 即视为有效（脚本或二进制均可）。 */
    private fun isValidScriptFile(f: java.io.File): Boolean = runCatching {
        f.inputStream().use { it.read() != '<'.code }
    }.getOrDefault(false)

    /**
     * 执行指定版本的安全格机文件：复制到可执行目录（/data/local/tmp）赋可执行权限后运行，
     * 执行结束（含被停止）后删除该副本。文件可能是脚本或纯二进制，都按可执行文件处理。
     * root 用独立 su 进程（可被停止直接结束），ADB 走 Shizuku UserService。
     * @return 终端输出（含退出码）；无权限/文件不存在返回 null
     */
    suspend fun runSafeFormatScript(version: Int, onStep: suspend (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val src = java.io.File(SAFE_FORMAT_DIR, "$version.sh")
            if (!src.exists() || src.length() == 0L) return@withContext null
            val grant = PermissionManager.checkGrantType()
            if (grant == PermissionGrantType.NONE) return@withContext null
            // su 直接读 App 私有目录会被 SELinux 拒绝，故先由 App 端复制到 shell 也能访问的中转目录
            val bridge = java.io.File(workDir(), "luoxi_safe_$version")
            if (runCatching { src.copyTo(bridge, overwrite = true) }.isFailure) {
                return@withContext null
            }
            val execPath = "/data/local/tmp/luoxi_safe_$version"
            onStep("正在执行安全格机文件")
            // 复制到可执行目录 → 赋可执行权限 → 执行 → 执行结束删除两个副本
            val cmd = "cp '${bridge.absolutePath}' '$execPath' && chmod 755 '$execPath' && '$execPath' 2>&1; ec=\$?; rm -f '$execPath' '${bridge.absolutePath}'; echo \"[exit code: \$ec]\""
            try {
                if (grant == PermissionGrantType.ROOT || grant == PermissionGrantType.BOTH) {
                    val process = try {
                        ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    } catch (t: Throwable) {
                        return@withContext null
                    }
                    runningScript = process
                    val sb = StringBuilder()
                    runCatching {
                        process.inputStream.bufferedReader().use { r ->
                            while (true) {
                                val line = r.readLine() ?: break
                                sb.append(line).append('\n')
                            }
                        }
                    }
                    val code = runCatching { process.waitFor() }.getOrDefault(-1)
                    if (runningScript === process) runningScript = null
                    if (sb.isEmpty()) sb.append("[exit code: $code]")
                    sb.toString()
                } else {
                    exec(cmd, timeoutMs = 10 * 60_000L)
                }
            } finally {
                runCatching { bridge.delete() }
            }
        }

    /** 当前正在执行的安全格机脚本进程（root 下用于停止）。 */
    @Volatile
    private var runningScript: Process? = null

    /** 停止正在执行的安全格机脚本：结束脚本进程及其解出的子进程。返回停止命令的输出（便于排查）。 */
    suspend fun stopSafeFormatScript(version: Int): String? {
        runningScript?.let { runCatching { it.destroyForcibly() } }
        runningScript = null
        // 扫 /proc 命中脚本本体或 .imgui_ 主程序；只对“父进程不是目标”的顶层目标发 SIGINT，
        // 使其子进程脱离父进程被系统收养继续运行（不牵连子进程）。
        val cmd = "pids=\$(grep -lsa -e '/data/local/tmp/luoxi_safe_$version' -e '/data/local/tmp/.imgui_' /proc/[0-9]*/cmdline 2>/dev/null | sed 's#/proc/##; s#/cmdline##'); " +
                "for p in \$pids; do pp=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status 2>/dev/null); case \" \$pids \" in *\" \$pp \"*) continue;; esac; kill -2 \"\$p\" 2>/dev/null; done; echo done"
        return runCatching { exec(cmd, timeoutMs = 10_000L) }.getOrNull()
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
