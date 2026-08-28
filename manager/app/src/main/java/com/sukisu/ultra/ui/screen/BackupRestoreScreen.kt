package com.sukisu.ultra.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.FileManagerUtils
import com.sukisu.ultra.ui.util.FileManagerUtils.BackupResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份还原页（Material3 重写版，逻辑移植自 luoxi-toolbox）：
 * 备份游戏文件 / 还原备份（备份目录 zip / 自定义 zip）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun BackupRestoreScreen(navigator: DestinationsNavigator) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份还原") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ),
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "backup") {
                BackupCard()
            }
            item(key = "restore") {
                Column(Modifier.padding(top = 12.dp)) {
                    RestoreBackupCard()
                }
            }
            item(key = "bottom") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 备份板块：复制游戏目录文件 → 压缩 zip → 存入 luoxi/备份/，不改动游戏目录。
 * 流程：确认弹窗 → 进度/结果弹窗。
 */
@Composable
private fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
    var doneTitle by remember { mutableStateOf("正在备份") }
    var showConfirm by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "备份游戏文件",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "复制游戏加载图目录文件并压缩保存到 luoxi/备份/，不改动游戏目录",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showConfirm = true },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("备份")
                }
            }
        }
    }

    if (showConfirm) {
        Dialog(onDismissRequest = { if (!running) showConfirm = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "是否确认备份",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "备份将保存到 luoxi/备份/，可随时还原",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showConfirm = false },
                            enabled = !running
                        ) {
                            Text("取消")
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                showConfirm = false
                                showProgress = true
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
                                    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("确认")
                        }
                    }
                }
            }
        }
    }

    if (showProgress) {
        StepDialog(
            title = doneTitle,
            stepText = stepText.ifEmpty { "准备中…" },
            running = running,
            onDismiss = { if (!running) { showProgress = false; stepText = "" } }
        )
    }
}

/**
 * 还原备份板块：选择备份文件（自定义文件 / 备份目录列表）→ 确认 → 进度/结果。
 */
@Composable
private fun RestoreBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showPick by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }

    // 待还原来源：type: "backup" = 备份目录文件名，"custom" = 自定义 zip 文件
    var pendingType by remember { mutableStateOf<String?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingCustomZip by remember { mutableStateOf<File?>(null) }

    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
    var doneTitle by remember { mutableStateOf("正在还原") }

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
                    showPick = false
                    showConfirm = true
                } else {
                    Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "还原备份",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从备份目录或自定义 zip 还原游戏加载图文件",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        showPick = true
                        scope.launch {
                            backups = FileManagerUtils.listBackups()
                        }
                    },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("还原")
                }
            }
        }
    }

    // 选择备份文件弹窗
    if (showPick) {
        Dialog(onDismissRequest = { showPick = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "选择备份文件",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "自定义文件",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                customLauncher.launch(arrayOf("*/*"))
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = "选择本地 zip 文件…",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(14.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "备份目录",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))

                    if (backups.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = "备份目录为空",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(((backups.size.coerceAtMost(5)) * 52).dp)
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
                                            showPick = false
                                            showConfirm = true
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 确认弹窗
    if (showConfirm) {
        Dialog(onDismissRequest = { if (!running) showConfirm = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "是否确认还原",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "将以 $pendingName 还原游戏加载图文件",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showConfirm = false },
                            enabled = !running
                        ) {
                            Text("取消")
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                val type = pendingType ?: return@Button
                                showConfirm = false
                                showProgress = true
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
                                    stepText = if (ok) "还原已完成" else "还原失败，请检查权限/备份文件"
                                    Toast.makeText(
                                        context,
                                        if (ok) "还原已完成" else "还原失败，请检查权限/备份文件",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("确认")
                        }
                    }
                }
            }
        }
    }

    if (showProgress) {
        StepDialog(
            title = doneTitle,
            stepText = stepText.ifEmpty { "准备中…" },
            running = running,
            onDismiss = { if (!running) { showProgress = false; stepText = "" } }
        )
    }
}

/** 通用步骤/结果弹窗 */
@Composable
private fun StepDialog(
    title: String,
    stepText: String,
    running: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stepText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                if (!running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("知道了")
                        }
                    }
                }
            }
        }
    }
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
