package me.weishu.kernelsu.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import me.weishu.kernelsu.ui.util.FileManagerUtils.ReplaceResult
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/** 已选图片（内存缓存，退出应用自动清除；uri 为 SAF uri 或裁剪后的本地文件 uri） */
data class SelectedImage(val uri: Uri)

/** 屏幕分辨率（px），横屏方向 */
data class ScreenSize(val longSide: Int, val shortSide: Int)

fun getScreenSize(context: android.content.Context): ScreenSize {
    val wm = context.getSystemService(WindowManager::class.java)
    val b = wm.currentWindowMetrics.bounds
    return ScreenSize(maxOf(b.width(), b.height()), minOf(b.width(), b.height()))
}

/**
 * 文件管理页（原超级用户页）
 */
@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    var images by remember { mutableStateOf(listOf<SelectedImage>()) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                },
                color = Color.Transparent,
                title = stringResource(R.string.file_manager),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp)
                .hazeSource(state = hazeState),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            // 拆分为多个 item：图片增减不会导致整页重新锚定（修复滚动跳底）
            item(key = "picker") {
                ImagePickerCard(
                    onPick = { uris -> images = images + uris.map { SelectedImage(it) } }
                )
            }
            itemsIndexed(
                images,
                key = { idx, _ -> "img$idx" },
                contentType = { _, _ -> "selected_image" }
            ) { idx, img ->
                SelectedImageItem(
                    modifier = Modifier.padding(top = 12.dp),
                    image = img,
                    onRemove = { images = images.filterIndexed { i, _ -> i != idx } },
                    onClick = { editingIdx = idx }
                )
            }
            item(key = "make") {
                Column(Modifier.padding(top = 12.dp)) {
                    MakeFilesCard(images = images)
                }
            }
            item(key = "replace") {
                Column(Modifier.padding(top = 12.dp)) {
                    ReplaceFilesCard()
                }
            }
            item(key = "bottom") {
                // 用底部导航栏高度（已含系统导航条），保证替换文件按钮不被底部栏遮挡
                Spacer(Modifier.height(bottomInnerPadding + 12.dp))
            }
        }
    }

    val editIdx = editingIdx
    if (editIdx != null && editIdx < images.size) {
        CropDialog(
            index = editIdx,
            image = images[editIdx],
            onDismiss = { editingIdx = null },
            onCropped = { i, uri ->
                images = images.toMutableList().also { it[i] = SelectedImage(uri) }
                editingIdx = null
            }
        )
    }
}

/**
 * 选择图片板块头部（系统图片选择器，可多选）；已选图片以独立列表项展示
 */
@Composable
private fun ImagePickerCard(
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.select_image),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.select_image_summary),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.select_image_button),
                    onClick = {
                        launcher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/**
 * 单个已选图片项：预览图 + 裁剪状态 + 右上角删除按钮，点击整项打开裁剪
 */
@Composable
private fun SelectedImageItem(
    modifier: Modifier = Modifier,
    image: SelectedImage,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val bitmap = remember(image.uri) {
        decodeSampledBitmap(image.uri, 128)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        insideMargin = PaddingValues(12.dp)
    ) {
        Row(
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
                        .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.1f))
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
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = image.uri.toString(),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 2
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "删除",
                    tint = colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/**
 * 裁剪弹窗：
 * - 图片按原始比例完整显示（画布比例 = 图片比例）
 * - 裁剪框比例固定为本机分辨率（横屏 长边:短边），始终限制在图片内部
 * - 按住框内拖动 = 移动；按住四个角任意一角拖动 = 缩放（保持比例，对角固定）
 * - 确定后直接裁剪：结果替换列表中的原图，并保存到 luoxi/裁剪/
 */
@Composable
private fun CropDialog(
    index: Int,
    image: SelectedImage,
    onDismiss: () -> Unit,
    onCropped: (Int, Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = remember { getScreenSize(context) }

    // 裁剪框高/宽 = 本机分辨率横屏比例（短边/长边）
    val ratio = screen.shortSide.toFloat() / screen.longSide.toFloat()

    val src = remember(image.uri) { decodeSampledBitmap(image.uri, 2048) }
    // 裁剪框（图片像素坐标），默认 = 图片内能放下的最大等比框，居中
    var box by remember(image.uri) {
        mutableStateOf(src?.let { defaultCropBox(it.width.toFloat(), it.height.toFloat(), ratio) })
    }
    var saving by remember { mutableStateOf(false) }
    val show = remember { mutableStateOf(true) }

    SuperDialog(
        show = show,
        title = "裁剪图片",
        onDismissRequest = { if (!saving) { show.value = false; onDismiss() } },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.crop_guide, "${screen.longSide}×${screen.shortSide}"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                if (src != null && box != null) {
                    // 画布尺寸跟随图片比例（宽上限 300dp、高上限 280dp）
                    val maxW = 300.dp
                    val maxH = 280.dp
                    val aspect = src.width.toFloat() / src.height.toFloat()
                    val dispW: Dp
                    val dispH: Dp
                    if (maxW / maxH > aspect) {
                        dispW = maxH * aspect; dispH = maxH
                    } else {
                        dispW = maxW; dispH = maxW / aspect
                    }

                    Canvas(
                        modifier = Modifier
                            .size(dispW, dispH)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .pointerInput(src) {
                                var mode = 0   // 0=无操作 1=移动框 2=缩放角
                                var corner = 0 // 0=左上 1=右上 2=左下 3=右下
                                detectDragGestures(
                                    onDragStart = { p ->
                                        val s = size.width.toFloat() / src.width
                                        val b = box ?: return@detectDragGestures
                                        val grab = 26.dp.toPx()
                                        val corners = arrayOf(
                                            Offset(b.left * s, b.top * s),
                                            Offset(b.right * s, b.top * s),
                                            Offset(b.left * s, b.bottom * s),
                                            Offset(b.right * s, b.bottom * s)
                                        )
                                        val hit = corners.indexOfFirst { (it - p).getDistance() <= grab }
                                        if (hit >= 0) {
                                            mode = 2; corner = hit
                                        } else if (
                                            p.x >= b.left * s && p.x <= b.right * s &&
                                            p.y >= b.top * s && p.y <= b.bottom * s
                                        ) {
                                            mode = 1
                                        } else {
                                            mode = 0
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        val b = box ?: return@detectDragGestures
                                        val s = size.width.toFloat() / src.width
                                        val imgW = src.width.toFloat()
                                        val imgH = src.height.toFloat()
                                        if (mode == 1) {
                                            // 整体移动，钳制在图片内
                                            val w = b.width(); val h = b.height()
                                            val nl = (b.left + drag.x / s).coerceIn(0f, imgW - w)
                                            val nt = (b.top + drag.y / s).coerceIn(0f, imgH - h)
                                            box = RectF(nl, nt, nl + w, nt + h)
                                        } else if (mode == 2) {
                                            // 对角固定，向拖动方向缩放，保持比例且不越出图片
                                            val px = change.position.x / s
                                            val py = change.position.y / s
                                            val fixX = if (corner == 0 || corner == 2) b.right else b.left
                                            val fixY = if (corner == 0 || corner == 1) b.bottom else b.top
                                            val dx = px - fixX
                                            val dy = py - fixY
                                            val dirX = if (dx >= 0f) 1f else -1f
                                            val dirY = if (dy >= 0f) 1f else -1f
                                            val availX = if (dirX > 0f) imgW - fixX else fixX
                                            val availY = if (dirY > 0f) imgH - fixY else fixY
                                            val wMax = minOf(availX, availY / ratio, minOf(imgW, imgH / ratio))
                                            val wMin = minOf(24f, wMax)
                                            var w = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy) / ratio)
                                            w = w.coerceIn(wMin, wMax)
                                            val h = w * ratio
                                            box = RectF(
                                                minOf(fixX, fixX + dirX * w),
                                                minOf(fixY, fixY + dirY * h),
                                                maxOf(fixX, fixX + dirX * w),
                                                maxOf(fixY, fixY + dirY * h)
                                            )
                                        }
                                    }
                                )
                            }
                    ) {
                        val cw = size.width
                        val ch = size.height
                        val s = cw / src.width
                        // 图片铺满画布（画布比例 = 图片比例，不变形）
                        drawImage(
                            image = src.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(src.width, src.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(cw.toInt(), ch.toInt()),
                        )
                        val b = box!!
                        val bl = b.left * s; val bt = b.top * s
                        val br = b.right * s; val bb = b.bottom * s
                        // 框外调暗（四块）
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, size = androidx.compose.ui.geometry.Size(cw, bt))
                        drawRect(dim, topLeft = Offset(0f, bb), size = androidx.compose.ui.geometry.Size(cw, ch - bb))
                        drawRect(dim, topLeft = Offset(0f, bt), size = androidx.compose.ui.geometry.Size(bl, bb - bt))
                        drawRect(dim, topLeft = Offset(br, bt), size = androidx.compose.ui.geometry.Size(cw - br, bb - bt))
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
                    Text("图片加载失败", color = colorScheme.onSurfaceVariantSummary)
                }

                Spacer(Modifier.height(6.dp))
                if (saving) {
                    Text("正在裁剪…", fontSize = 12.sp, color = colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.crop_confirm_content),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            enabled = !saving,
                            onClick = { show.value = false; onDismiss() }
                        )
                        TextButton(
                            text = stringResource(R.string.confirm),
                            enabled = !saving && box != null,
                            onClick = {
                                val b = box ?: return@TextButton
                                val preview = src ?: return@TextButton
                                scope.launch {
                                    saving = true
                                    var published = false
                                    val newUri = withContext(Dispatchers.IO) {
                                        performCrop(image.uri, preview, b) { f ->
                                            published = FileManagerUtils.publishCropFile(f)
                                        }
                                    }
                                    saving = false
                                    if (newUri != null) {
                                        show.value = false
                                        onCropped(index, newUri)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (published) "已裁剪并保存到 luoxi/裁剪/" else "已裁剪（保存到裁剪目录失败）",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "裁剪失败", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
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
 * 制作成文件板块：
 * 按记录的游戏文件数复制所选图片并逐个命名，输出到 luoxi/文件输出/
 * 例：80 个文件名 + 1 张图 = 复制 80 份；2 张图 = 每张 40 份；3 张图 = 各 26 份 + 余 2 份随机分。
 */
@Composable
private fun MakeFilesCard(images: List<SelectedImage>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf<String?>(null) }
    val stepShow = remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.file_output_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.file_output_summary),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 清理缓存（制作文件按钮左边）：清空文件输出 + 裁剪目录
                TextButton(
                    text = if (clearing) "清理中…" else "清理缓存",
                    enabled = !clearing && !making,
                    onClick = {
                        scope.launch {
                            clearing = true
                            val ok = FileManagerUtils.clearCacheDirs()
                            clearing = false
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已清空「文件输出」与「裁剪」目录" else "清理失败，请检查权限",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                TextButton(
                    text = if (making) "制作中…" else "制作文件",
                    enabled = !making && images.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            making = true
                            stepShow.value = true
                            val ok = makeFiles(images) { step ->
                                withContext(Dispatchers.Main) { stepText = step }
                            }
                            making = false
                            withContext(Dispatchers.Main) {
                                stepText = if (ok) "制作完成" else "制作失败"
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "制作完成，可在「文件输出」目录查看" else "制作失败，请检查权限/文件名记录",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    SuperDialog(
        show = stepShow,
        title = "制作文件",
        onDismissRequest = { if (!making) stepShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stepText ?: "准备中…",
                    fontSize = 14.sp,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                if (!making) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = stringResource(R.string.got_it),
                            onClick = { stepShow.value = false },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    )
}

/**
 * 制作文件：
 * 1. 读记录的游戏文件名 N 个；2. 图片 M 张（已裁剪）均分（各 N/M 份，余数随机多一份）；
 * 3. 按游戏文件名逐个字节复制到中转目录（保留原始格式与画质）；
 * 4. shell 移入 文件输出/（先清空旧输出）。
 */
private suspend fun makeFiles(
    images: List<SelectedImage>,
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

    val assign = mutableListOf<Pair<SelectedImage, String>>()
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

/**
 * 替换游戏文件板块：删除游戏目录（LoadingBG）内文件，把制作好的文件移进去；可选先备份。
 */
@Composable
private fun ReplaceFilesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var stepText by remember { mutableStateOf("") }
    val confirmShow = remember { mutableStateOf(false) }
    val progressShow = remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.replace_game_files_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.replace_game_files_summary),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.replace_files_button),
                    enabled = !running,
                    onClick = { confirmShow.value = true },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    SuperDialog(
        show = confirmShow,
        title = "是否备份替换",
        onDismissRequest = { confirmShow.value = false },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.replace_confirm_content),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = stringResource(R.string.cancel), onClick = { confirmShow.value = false })
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    TextButton(
                        text = stringResource(R.string.replace_no_backup),
                        enabled = !running,
                        onClick = {
                            confirmShow.value = false
                            startReplace(scope, false, context) { s, r ->
                                stepText = s; running = r; progressShow.value = true
                            }
                        }
                    )
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    TextButton(
                        text = stringResource(R.string.backup_button),
                        enabled = !running,
                        onClick = {
                            confirmShow.value = false
                            startReplace(scope, true, context) { s, r ->
                                stepText = s; running = r; progressShow.value = true
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    SuperDialog(
        show = progressShow,
        title = "正在替换",
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
                            text = stringResource(R.string.got_it),
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

private fun startReplace(
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
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------- 图片处理 ----------

/**
 * 默认裁剪框：图片内能放下的最大等比框（比例 = 本机分辨率横屏），居中。
 */
private fun defaultCropBox(imgW: Float, imgH: Float, ratio: Float): RectF {
    val w = minOf(imgW, imgH / ratio)
    val h = w * ratio
    val l = (imgW - w) / 2f
    val t = (imgH - h) / 2f
    return RectF(l, t, l + w, t + h)
}

/**
 * 执行裁剪：
 * 以更高分辨率（≤4096）重新解码原图，把预览坐标系的裁剪框映射回原坐标并裁剪，
 * 结果存为中转目录 JPEG 文件，并通过 onPublish 复制到 luoxi/裁剪/。
 * @return 裁剪结果文件 uri；失败返回 null
 */
private suspend fun performCrop(
    uri: Uri,
    preview: Bitmap,
    box: RectF,
    onPublish: suspend (java.io.File) -> Unit
): Uri? {
    return try {
        val full = decodeSampledBitmap(uri, 4096) ?: preview
        val fx = full.width.toFloat() / preview.width
        val fy = full.height.toFloat() / preview.height
        val l = (box.left * fx).toInt().coerceIn(0, full.width - 1)
        val t = (box.top * fy).toInt().coerceIn(0, full.height - 1)
        val r = (box.right * fx).toInt().coerceIn(l + 1, full.width)
        val b = (box.bottom * fy).toInt().coerceIn(t + 1, full.height)
        val cropped = Bitmap.createBitmap(full, l, t, r - l, b - t)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val f = java.io.File(FileManagerUtils.workDir(), "crop_$stamp.jpg")
        java.io.FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (cropped !== full) cropped.recycle()
        if (full !== preview) full.recycle()
        onPublish(f)
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }
}

/**
 * 解码 URI 图片为采样位图（保持原始比例，仅降采样防 OOM）
 */
private fun decodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
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
