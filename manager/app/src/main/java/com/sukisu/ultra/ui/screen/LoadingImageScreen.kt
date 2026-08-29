package com.sukisu.ultra.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.RectF
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.BackupRestoreScreenDestination
import com.ramcosta.composedestinations.generated.destinations.LoadingEditScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.theme.CardConfig
import com.sukisu.ultra.ui.theme.CardConfig.cardAlpha
import com.sukisu.ultra.ui.util.FileManagerUtils
import com.sukisu.ultra.ui.util.FileManagerUtils.ReplaceResult
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已选图片（内存缓存，退出应用自动清除；uri 为 SAF uri 或裁剪后的本地文件 uri） */
data class LoadingSelectedImage(val uri: Uri)

/** 屏幕分辨率（px），横屏方向 */
data class LoadingScreenSize(val longSide: Int, val shortSide: Int)

private fun getScreenSize(context: android.content.Context): LoadingScreenSize {
    val wm = context.getSystemService(WindowManager::class.java)
    val b = wm.currentWindowMetrics.bounds
    return LoadingScreenSize(maxOf(b.width(), b.height()), minOf(b.width(), b.height()))
}

/**
 * 制作加载图入口页：两个可点击板块——加载图修改 / 备份还原
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun LoadingImageScreen(navigator: DestinationsNavigator) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val cardAlpha = CardConfig.cardAlpha
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("制作加载图") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cardColor.copy(alpha = cardAlpha),
                    scrolledContainerColor = cardColor.copy(alpha = cardAlpha)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingEntryCard(
                title = "加载图修改",
                subtitle = "选择图片、裁剪并制作成游戏加载文件，替换到游戏目录",
                onClick = { navigator.navigate(LoadingEditScreenDestination) }
            )
            LoadingEntryCard(
                title = "备份还原",
                subtitle = "备份游戏加载图文件，或从备份目录 / 自定义 zip 还原",
                onClick = { navigator.navigate(BackupRestoreScreenDestination) }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 入口卡片：大标题 + 小标题 + 箭头 */
@Composable
private fun LoadingEntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 加载图修改页：选图 → 逐张裁剪（横屏比例）→ 制作文件 → 替换游戏文件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun LoadingEditScreen(navigator: DestinationsNavigator) {
    var images by remember { mutableStateOf(listOf<LoadingSelectedImage>()) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val cardAlpha = CardConfig.cardAlpha

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("加载图修改") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cardColor.copy(alpha = cardAlpha),
                    scrolledContainerColor = cardColor.copy(alpha = cardAlpha)
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
            // 拆分为多个 item：图片增减不会导致整页重新锚定（修复滚动跳底）
            item(key = "picker") {
                LoadingImagePickerCard(
                    onPick = { uris -> images = images + uris.map { LoadingSelectedImage(it) } }
                )
            }
            itemsIndexed(
                images,
                key = { idx, _ -> "img$idx" },
                contentType = { _, _ -> "selected_image" }
            ) { idx, img ->
                LoadingSelectedImageItem(
                    modifier = Modifier.padding(top = 12.dp),
                    image = img,
                    onRemove = { images = images.filterIndexed { i, _ -> i != idx } },
                    onClick = { editingIdx = idx }
                )
            }
            item(key = "make") {
                Column(Modifier.padding(top = 12.dp)) {
                    LoadingMakeFilesCard(images = images)
                }
            }
            item(key = "replace") {
                Column(Modifier.padding(top = 12.dp)) {
                    LoadingReplaceFilesCard()
                }
            }
            item(key = "bottom") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val editIdx = editingIdx
    if (editIdx != null && editIdx < images.size) {
        LoadingCropDialog(
            index = editIdx,
            image = images[editIdx],
            onDismiss = { editingIdx = null },
            onCropped = { i, uri ->
                images = images.toMutableList().also { it[i] = LoadingSelectedImage(uri) }
                editingIdx = null
            }
        )
    }
}

/** 选择图片板块（系统图片选择器，可多选） */
@Composable
private fun LoadingImagePickerCard(
    onPick: (List<Uri>) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) onPick(uris)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
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
                text = "选择图片",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从相册选择图片，选好后逐张裁剪为游戏加载图比例",
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
                        launcher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("选择图片")
                }
            }
        }
    }
}

/** 单个已选图片项：预览图 + 裁剪状态 + 删除按钮，点击整项打开裁剪 */
@Composable
private fun LoadingSelectedImageItem(
    modifier: Modifier = Modifier,
    image: LoadingSelectedImage,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val bitmap = remember(image.uri) {
        loadingDecodeSampledBitmap(image.uri, 128)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = if (image.uri.scheme == "file") "已裁剪 · 点击可再次裁剪" else "未裁剪 · 点击裁剪",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = image.uri.toString(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 裁剪弹窗（横屏比例 = 短边/长边）：
 * - 图片按原始比例完整显示（画布比例 = 图片比例）
 * - 裁剪框比例固定为本机分辨率横屏比例，不可更改
 * - 按住框内拖动 = 移动；按住四角任意一角拖动 = 等比例缩放（对角固定）
 * - 裁剪框始终被图片边界限制
 * - 裁剪结果替换列表原项并保存到 luoxi/裁剪/
 */
@Composable
private fun LoadingCropDialog(
    index: Int,
    image: LoadingSelectedImage,
    onDismiss: () -> Unit,
    onCropped: (Int, Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = remember { getScreenSize(context) }
    val cropRatio = screen.shortSide.toFloat() / screen.longSide.toFloat()

    val src = remember(image.uri) { loadingDecodeSampledBitmap(image.uri, 2048) }
    var normBox by remember(image.uri) {
        mutableStateOf(src?.let { loadingDefaultNormBox(it.width.toFloat(), it.height.toFloat(), cropRatio) })
    }
    var saving by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
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
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "裁剪图片",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { if (!saving) onDismiss() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "取消",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "裁剪框比例为本机分辨率横屏 ${screen.longSide}×${screen.shortSide}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))

                    if (src != null && normBox != null) {
                        val imgW = src.width.toFloat()
                        val imgH = src.height.toFloat()
                        val imgAspect = imgW / imgH

                        // 画布尺寸跟随图片比例（宽上限 320dp、高上限 280dp）
                        val maxW = 320.dp
                        val maxH = 280.dp
                        val dispW: Dp
                        val dispH: Dp
                        if (maxW / maxH > imgAspect) {
                            dispW = maxH * imgAspect; dispH = maxH
                        } else {
                            dispW = maxW; dispH = maxW / imgAspect
                        }

                        Canvas(
                            modifier = Modifier
                                .size(dispW, dispH)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                                .pointerInput(src) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val nb = normBox ?: return@awaitEachGesture
                                        val cwR = size.width.toFloat()
                                        val chR = size.height.toFloat()
                                        val s = cwR / imgW
                                        val bl = nb.left * imgW * s
                                        val bt = nb.top * imgH * s
                                        val br = nb.right * imgW * s
                                        val bb = nb.bottom * imgH * s
                                        val grab = with(density) { 26.dp.toPx() }
                                        val corners = arrayOf(
                                            Offset(bl, bt), Offset(br, bt),
                                            Offset(bl, bb), Offset(br, bb)
                                        )
                                        val hit = corners.indexOfFirst { (it - down.position).getDistance() <= grab }
                                        val mode: Int
                                        val corner: Int
                                        if (hit >= 0) {
                                            mode = 2; corner = hit
                                        } else if (down.position.x >= bl && down.position.x <= br && down.position.y >= bt && down.position.y <= bb) {
                                            mode = 1; corner = 0
                                        } else {
                                            // 框外按下：不消费，交给父级滚动（图片底部可滚出来）
                                            return@awaitEachGesture
                                        }

                                        down.consume()
                                        var lastX = down.position.x
                                        var lastY = down.position.y
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            val dx = change.position.x - lastX
                                            val dy = change.position.y - lastY
                                            lastX = change.position.x
                                            lastY = change.position.y
                                            val cur = normBox ?: break
                                            if (mode == 1) {
                                                // 整体移动：水平用 cwR/imgW，垂直用 chR/imgH，换算精确
                                                val dnX = dx / s / imgW
                                                val dnY = dy * imgH / chR
                                                val nw = cur.right - cur.left
                                                val nh = cur.bottom - cur.top
                                                val nl = (cur.left + dnX).coerceIn(0f, 1f - nw)
                                                val nt = (cur.top + dnY).coerceIn(0f, 1f - nh)
                                                normBox = RectF(nl, nt, nl + nw, nt + nh)
                                            } else if (mode == 2) {
                                                // 对角固定缩放
                                                val px = change.position.x / s
                                                val py = change.position.y * imgH / chR
                                                val fixX = if (corner == 0 || corner == 2) cur.right * imgW else cur.left * imgW
                                                val fixY = if (corner == 0 || corner == 1) cur.bottom * imgH else cur.top * imgH
                                                val dx2 = px - fixX
                                                val dy2 = py - fixY
                                                val dirX = if (dx2 >= 0f) 1f else -1f
                                                val dirY = if (dy2 >= 0f) 1f else -1f
                                                val availX = if (dirX > 0f) imgW - fixX else fixX
                                                val availY = if (dirY > 0f) imgH - fixY else fixY
                                                val wMax = minOf(availX, availY / cropRatio, minOf(imgW, imgH / cropRatio))
                                                val wMin = minOf(24f, wMax)
                                                var w = maxOf(kotlin.math.abs(dx2), kotlin.math.abs(dy2) / cropRatio)
                                                w = w.coerceIn(wMin, wMax)
                                                val h = w * cropRatio
                                                val nl = (minOf(fixX, fixX + dirX * w) / imgW).coerceIn(0f, 1f)
                                                val nt = (minOf(fixY, fixY + dirY * h) / imgH).coerceIn(0f, 1f)
                                                val nr = (maxOf(fixX, fixX + dirX * w) / imgW).coerceIn(0f, 1f)
                                                val nb2 = (maxOf(fixY, fixY + dirY * h) / imgH).coerceIn(0f, 1f)
                                                normBox = RectF(nl, nt, nr, nb2)
                                            }
                                        }
                                    }
                                }
                        ) {
                            val cwR = size.width.roundToInt().toFloat()
                            val chR = size.height.roundToInt().toFloat()
                            val s = cwR / imgW
                            // 图片铺满画布（画布比例 = 图片比例，不变形）
                            drawImage(
                                image = src.asImageBitmap(),
                                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                                srcSize = androidx.compose.ui.unit.IntSize(src.width, src.height),
                                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                                dstSize = androidx.compose.ui.unit.IntSize(cwR.toInt(), chR.toInt()),
                            )
                            // 归一化 → 画布像素坐标
                            val nb = normBox!!
                            val bl = nb.left * imgW * s; val bt = nb.top * imgH * s
                            val br = nb.right * imgW * s; val bb = nb.bottom * imgH * s
                            // 框外压暗（四块）
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(dim, size = androidx.compose.ui.geometry.Size(cwR, bt))
                            drawRect(dim, topLeft = Offset(0f, bb), size = androidx.compose.ui.geometry.Size(cwR, chR - bb))
                            drawRect(dim, topLeft = Offset(0f, bt), size = androidx.compose.ui.geometry.Size(bl, bb - bt))
                            drawRect(dim, topLeft = Offset(br, bt), size = androidx.compose.ui.geometry.Size(cwR - br, bb - bt))
                            // 虚线裁剪框
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(bl, bt),
                                size = androidx.compose.ui.geometry.Size(br - bl, bb - bt),
                                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                            )
                            // 四角手柄
                            val hr = 6.dp.toPx()
                            listOf(Offset(bl, bt), Offset(br, bt), Offset(bl, bb), Offset(br, bb)).forEach { c ->
                                drawCircle(Color.White, radius = hr, center = c)
                                drawCircle(Color.Black.copy(alpha = 0.35f), radius = hr, center = c, style = Stroke(2f))
                            }
                        }
                    } else {
                        Text(
                            text = "图片加载失败",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    if (saving) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "正在裁剪…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "裁剪后图片将用于制作游戏加载文件",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { if (!saving) onDismiss() },
                                enabled = !saving
                            ) {
                                Text("取消")
                            }
                            Button(
                                onClick = {
                                    val nb = normBox ?: return@Button
                                    scope.launch {
                                        saving = true
                                        var published = false
                                        val newUri = withContext(Dispatchers.IO) {
                                            performCrop(image.uri, nb, cropRatio) { f ->
                                                published = FileManagerUtils.publishCropFile(f)
                                            }
                                        }
                                        saving = false
                                        if (newUri != null) {
                                            onDismiss()
                                            onCropped(index, newUri)
                                            Toast.makeText(
                                                context,
                                                if (published) "已裁剪并保存到 luoxi/裁剪/" else "已裁剪（保存到裁剪目录失败）",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(context, "裁剪失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !saving && normBox != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("确定")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 制作成文件板块：按记录的游戏文件数复制所选图片并逐个命名，输出到 luoxi/文件输出/
 */
@Composable
private fun LoadingMakeFilesCard(images: List<LoadingSelectedImage>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf<String?>(null) }
    var showStep by remember { mutableStateOf(false) }

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
                text = "制作文件",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "按记录的游戏文件名数量均分复制所选图片，输出到 luoxi/文件输出/",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clearing = true
                            val ok = FileManagerUtils.clearCacheDirs()
                            clearing = false
                            Toast.makeText(
                                context,
                                if (ok) "已清空「文件输出」与「裁剪」目录" else "清理失败，请检查权限",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !clearing && !making
                ) {
                    Text(if (clearing) "清理中…" else "清理缓存")
                }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            making = true
                            showStep = true
                            stepText = "准备中…"
                            val ok = makeFiles(images) { step ->
                                withContext(Dispatchers.Main) { stepText = step }
                            }
                            making = false
                            withContext(Dispatchers.Main) {
                                stepText = if (ok) "制作完成" else "制作失败"
                            }
                            Toast.makeText(
                                context,
                                if (ok) "制作完成，可在「文件输出」目录查看" else "制作失败，请检查权限/文件名记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !making && images.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (making) "制作中…" else "制作文件")
                }
            }
        }
    }

    if (showStep) {
        LoadingStepDialog(
            title = "制作文件",
            stepText = stepText,
            running = making,
            onDismiss = { if (!making) showStep = false }
        )
    }
}

/** 替换游戏文件板块：可选备份，替换 LoadingBG 目录 */
@Composable
private fun LoadingReplaceFilesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
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
                text = "替换游戏文件",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "把制作好的文件替换进游戏加载图目录（可先备份）",
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
                    Text("替换游戏文件")
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
                        text = "是否备份替换",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "备份后替换，可随时还原；不备份直接替换",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showConfirm = false }) { Text("取消") }
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            onClick = {
                                showConfirm = false
                                startLoadingReplace(scope, false, context) { s, r ->
                                    stepText = s; running = r; showProgress = true
                                }
                            },
                            enabled = !running
                        ) {
                            Text("不备份")
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                showConfirm = false
                                startLoadingReplace(scope, true, context) { s, r ->
                                    stepText = s; running = r; showProgress = true
                                }
                            },
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
        }
    }

    if (showProgress) {
        LoadingStepDialog(
            title = "正在替换",
            stepText = stepText.ifEmpty { "准备中…" },
            running = running,
            onDismiss = { if (!running) { showProgress = false; stepText = "" } }
        )
    }
}

/** 通用步骤/结果弹窗（Material3 Dialog） */
@Composable
private fun LoadingStepDialog(
    title: String,
    stepText: String?,
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
                    text = stepText ?: "准备中…",
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

/**
 * 制作文件：读记录的游戏文件名 N 个；图片 M 张均分（各 N/M 份，余数随机多一份）；
 * 按游戏文件名逐个字节复制到中转目录；shell 移入 文件输出/
 */
private suspend fun makeFiles(
    images: List<LoadingSelectedImage>,
    onStep: suspend (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    onStep("正在读取游戏文件名记录")
    val names = FileManagerUtils.readRecordedNames()
    if (names.isEmpty()) return@withContext false
    val imgs = images
    if (imgs.isEmpty()) return@withContext false

    val n = names.size
    val m = imgs.size
    val base = n / m
    val rem = n % m
    val extraIdx = imgs.indices.shuffled().take(rem).toSet()

    val assign = mutableListOf<Pair<LoadingSelectedImage, String>>()
    var idx = 0
    imgs.forEachIndexed { i, img ->
        repeat(base + if (i in extraIdx) 1 else 0) {
            assign.add(img to names[idx++])
        }
    }

    val staging = java.io.File(FileManagerUtils.workDir(), "make").apply {
        mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
    }

    var done = 0
    assign.forEach { (img, name) ->
        done++
        onStep("正在复制文件 $done/${assign.size}")
        try {
            val input = ksuApp.contentResolver.openInputStream(img.uri) ?: return@withContext false
            input.use {
                java.io.File(staging, name).outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }

    onStep("正在写入文件输出目录")
    FileManagerUtils.publishToOutput(staging)
}

private fun startLoadingReplace(
    scope: kotlinx.coroutines.CoroutineScope,
    withBackup: Boolean,
    context: android.content.Context,
    onUpdate: (String, Boolean) -> Unit
) {
    scope.launch {
        onUpdate("准备中…", true)
        val result = FileManagerUtils.replaceGameFiles(withBackup) { step ->
            withContext(Dispatchers.Main) { onUpdate(step, true) }
        }
        withContext(Dispatchers.Main) {
            val (done, toast) = when (result) {
                ReplaceResult.SUCCESS -> "替换完成" to "替换完成"
                ReplaceResult.NO_OUTPUT_FILES -> "没有制作好的文件" to "请先选择图片并制作文件"
                ReplaceResult.NO_PERMISSION -> "无权限" to "无 Root/Shizuku 权限，请先授权"
                ReplaceResult.GAME_DIR_EMPTY ->
                    "游戏目录为空" to "游戏目录为空，无法备份（可先选「不备份」重试）"
                ReplaceResult.BACKUP_FAILED -> "备份失败" to "备份失败，游戏目录未改动，可重试"
                ReplaceResult.MOVE_FAILED ->
                    "移动失败" to "移动文件失败，已尝试从备份回滚"
            }
            onUpdate(done, false)
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------- 图片处理 ----------

/** 默认归一化裁剪框：图片内能放下的最大等比框（比例 = 本机分辨率横屏），居中 */
private fun loadingDefaultNormBox(imgW: Float, imgH: Float, ratio: Float): RectF {
    val w = minOf(imgW, imgH / ratio)
    val h = w * ratio
    val l = (imgW - w) / 2f
    val t = (imgH - h) / 2f
    return RectF(l / imgW, t / imgH, (l + w) / imgW, (t + h) / imgH)
}

/**
 * 执行裁剪：用 BitmapRegionDecoder 只解码目标裁剪区域（不解全图，速度快很多），
 * 结果存为中转目录 JPEG 文件，并通过 onPublish 复制到 luoxi/裁剪/
 */
private suspend fun performCrop(
    uri: Uri,
    normBox: RectF,
    cropRatio: Float,
    onPublish: suspend (java.io.File) -> Unit
): Uri? {
    return try {
        // 先取原图全尺寸（仅读边界，很快）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val fw = bounds.outWidth.toFloat()
        val fh = bounds.outHeight.toFloat()
        val normW = normBox.right - normBox.left
        val boxW = normW * fw
        val boxH = boxW * cropRatio
        val cx = (normBox.left + normBox.right) / 2f * fw
        val cy = (normBox.top + normBox.bottom) / 2f * fh
        val l = (cx - boxW / 2f).roundToInt().coerceIn(0, bounds.outWidth - 1)
        val t = (cy - boxH / 2f).roundToInt().coerceIn(0, bounds.outHeight - 1)
        val r = (cx + boxW / 2f).roundToInt().coerceIn(l + 1, bounds.outWidth)
        val b = (cy + boxH / 2f).roundToInt().coerceIn(t + 1, bounds.outHeight)

        // 按目标区域尺寸采样（最长边 ≤4096），只解码该区域
        var sample = 1
        while (maxOf(r - l, b - t) / (sample * 2) >= 4096) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val cropped = ksuApp.contentResolver.openInputStream(uri)?.use { ins ->
            val decoder: BitmapRegionDecoder = BitmapRegionDecoder.newInstance(ins, false)
            try {
                decoder.decodeRegion(android.graphics.Rect(l, t, r, b), opts)
            } finally {
                decoder.recycle()
            }
        } ?: return null

        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val f = java.io.File(FileManagerUtils.workDir(), "crop_$stamp.jpg")
        java.io.FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cropped.recycle()
        onPublish(f)
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }
}

/** 解码 URI 图片为采样位图（保持原始比例，仅降采样防 OOM） */
private fun loadingDecodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}
