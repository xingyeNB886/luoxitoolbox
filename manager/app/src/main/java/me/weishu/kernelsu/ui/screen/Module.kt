package me.weishu.kernelsu.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.util.FileManagerUtils
import me.weishu.kernelsu.ui.util.FileManagerUtils.BackupResult
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/**
 * 功能页（原模块页）
 */
@Composable
fun ModulePager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                },
                color = Color.Transparent,
                title = stringResource(R.string.function),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .hazeSource(state = hazeState)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BackupCard()
                    RestoreBackupCard()
                }
                Spacer(Modifier.height(bottomInnerPadding))
            }
        }
    }
}

/**
 * 备份板块（功能页）：
 * 机制与「替换游戏文件」时的备份完全一致——复制游戏目录文件 → 压缩 zip → 存入 luoxi/备份/，不改动游戏目录。
 * 流程：确认弹窗（是否确认备份）→ 进度/结果弹窗（备份已完成 / 备份失败）。
 */
@Composable
private fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
    var doneTitle by remember { mutableStateOf("正在备份") }
    val confirmShow = remember { mutableStateOf(false) }
    val progressShow = remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "备份",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "将游戏目录内的加载图文件打包备份到 luoxi/备份/，不改动游戏目录",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "备份",
                    enabled = !running,
                    onClick = { confirmShow.value = true },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    // 确认弹窗
    SuperDialog(
        show = confirmShow,
        title = "是否确认备份",
        onDismissRequest = { if (!running) confirmShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "备份会将游戏目录内当前的加载图文件打包为 zip，保存到 luoxi/备份/，不会改动游戏目录。",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        enabled = !running,
                        onClick = { confirmShow.value = false }
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        text = "确定",
                        enabled = !running,
                        onClick = {
                            confirmShow.value = false
                            progressShow.value = true
                            running = true
                            stepText = "准备中…"
                            doneTitle = "正在备份"
                            scope.launch {
                                val result = FileManagerUtils.backupGameFiles { step ->
                                    withContext(Dispatchers.Main) { stepText = step }
                                }
                                running = false
                                val (title, toast) = when (result) {
                                    BackupResult.SUCCESS ->
                                        "备份已完成" to "备份完成，已保存到 luoxi/备份/"
                                    BackupResult.NO_PERMISSION ->
                                        "备份失败" to "无 Root/Shizuku 权限，请先授权"
                                    BackupResult.GAME_DIR_EMPTY ->
                                        "备份失败" to "游戏目录为空，无法备份"
                                    BackupResult.BACKUP_FAILED ->
                                        "备份失败" to "备份失败，请检查权限/游戏目录"
                                }
                                doneTitle = title
                                stepText = toast
                                android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    // 进度/结果弹窗
    SuperDialog(
        show = progressShow,
        title = doneTitle,
        onDismissRequest = { if (!running) progressShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (stepText.isEmpty()) "准备中…" else stepText,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                if (!running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "知道了",
                            onClick = {
                                progressShow.value = false
                                stepText = ""
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    )
}

/**
 * 还原备份板块：
 * 点击"还原"→ 选择备份文件弹窗（自定义文件 / 备份目录列表）
 * → 确认弹窗（是否确认还原）→ 进度/结果弹窗（还原已完成 / 还原失败）。
 * 确认与结果分离为两个弹窗，避免完成后停留在确认弹窗被误点再次还原。
 */
@Composable
private fun RestoreBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickShow = remember { mutableStateOf(false) }
    val confirmShow = remember { mutableStateOf(false) }
    val progressShow = remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }

    // 待还原来源：null = 未选择；type: "backup" = 备份目录文件名，"custom" = 自定义 zip 文件
    var pendingType by remember { mutableStateOf<String?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingCustomZip by remember { mutableStateOf<File?>(null) }

    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
    var doneTitle by remember { mutableStateOf("正在还原") }

    // 自定义文件选择（SAF）→ 复制到中转目录
    val customLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val f = copyToWork(uri)
                if (f != null) {
                    pendingType = "custom"
                    pendingCustomZip = f
                    pendingName = uri.lastPathSegment ?: "自定义备份"
                    pickShow.value = false
                    confirmShow.value = true
                } else {
                    android.widget.Toast.makeText(context, "读取文件失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "还原备份",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从备份压缩包还原游戏目录的加载图文件",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "还原",
                    enabled = !running,
                    onClick = {
                        pickShow.value = true
                        scope.launch {
                            backups = FileManagerUtils.listBackups()
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    // 选择备份文件弹窗
    SuperDialog(
        show = pickShow,
        title = "选择备份文件",
        onDismissRequest = { pickShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                // 上板块：自定义文件
                Text(
                    text = "选择自定义文件",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            customLauncher.launch(arrayOf("*/*"))
                        }
                ) {
                    Text(
                        text = "点击从文件管理器选择 zip 压缩包",
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "备份目录（luoxi/备份/）",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))

                // 下板块：备份目录里的压缩包列表
                if (backups.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无备份",
                            fontSize = 15.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(((backups.size.coerceAtMost(5)) * 56).dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        backups.forEach { name ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingType = "backup"
                                        pendingName = name
                                        pendingCustomZip = null
                                        pickShow.value = false
                                        confirmShow.value = true
                                    }
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 15.sp,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    // 确认弹窗：仅确认，不在此显示进度
    SuperDialog(
        show = confirmShow,
        title = "是否确认还原",
        onDismissRequest = { if (!running) confirmShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "备份文件：$pendingName\n还原将删除游戏目录内当前文件，并解压备份文件替换。",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        enabled = !running,
                        onClick = { confirmShow.value = false }
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        text = "确定",
                        enabled = !running,
                        onClick = {
                            val type = pendingType ?: return@TextButton
                            // 关闭确认弹窗，打开进度/结果弹窗，避免完成后停留在确认弹窗被再次触发
                            confirmShow.value = false
                            progressShow.value = true
                            running = true
                            stepText = "准备中…"
                            doneTitle = "正在还原"
                            scope.launch {
                                val ok = when (type) {
                                    "backup" -> FileManagerUtils.restoreBackup(pendingName) { step ->
                                        withContext(Dispatchers.Main) { stepText = step }
                                    }
                                    "custom" -> FileManagerUtils.restoreFromCustomFile(pendingCustomZip!!) { step ->
                                        withContext(Dispatchers.Main) { stepText = step }
                                    }
                                    else -> false
                                }
                                running = false
                                doneTitle = if (ok) "还原已完成" else "还原失败"
                                stepText = if (ok) "还原完成" else "还原失败，请检查权限/备份文件"
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) "还原完成" else "还原失败，请检查权限/备份文件",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    // 进度/结果弹窗：还原中显示步骤，完成后显示「还原已完成」/「还原失败」
    SuperDialog(
        show = progressShow,
        title = doneTitle,
        onDismissRequest = { if (!running) progressShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (stepText.isEmpty()) "准备中…" else stepText,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                if (!running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "知道了",
                            onClick = {
                                progressShow.value = false
                                stepText = ""
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    )
}

/** SAF Uri → 中转目录文件（app 外部私有目录，Java/shell 均可访问） */
private suspend fun copyToWork(uri: Uri): File? = withContext(Dispatchers.IO) {
    runCatching {
        val f = File(FileManagerUtils.workDir(), "luoxi_custom_backup.zip")
        ksuApp.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(f).use { input.copyTo(it) }
        } ?: return@runCatching null
        f
    }.getOrNull()
}
