package me.weishu.kernelsu.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

/**
 * 取景框参数（归一化到输出正方形画布的 0..1）：
 * cx/cy 框中心，w 框宽（框高 = 框宽 × 屏幕短边/长边，即本机真实分辨率横屏比例）
 */
data class CropParams(val cx: Float, val cy: Float, val w: Float)

/** 已选图片（内存缓存，退出应用自动清除） */
data class SelectedImage(val uri: Uri, val crop: CropParams?)

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
    var editing by remember { mutableStateOf<SelectedImage?>(null) }

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
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ImagePickerCard(
                        images = images,
                        onPick = { images = images + it.map { SelectedImage(it, null) } },
                        onRemove = { img -> images = images - img },
                        onPreview = { editing = it }
                    )
                    MakeFilesCard(images = images)
                    ReplaceFilesCard()
                }
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }

    editing?.let { img ->
        ViewfinderDialog(
            image = img,
            onDismiss = { editing = null },
            onConfirm = { crop ->
                images = images.map { if (it.uri == img.uri) it.copy(crop = crop) else it }
                editing = null
            }
        )
    }
}

/**
 * 选择图片板块（系统文件选择器，可多选，可浏览真实目录）
 */
@Composable
private fun ImagePickerCard(
    images: List<SelectedImage>,
    onPick: (List<Uri>) -> Unit,
    onRemove: (SelectedImage) -> Unit,
    onPreview: (SelectedImage) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onPick(uris)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "选择图片",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点击图片可预览并调整取景框，退出应用后自动清除",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "选择图片",
                    onClick = { launcher.launch(arrayOf("image/*")) },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }

            images.forEach { img ->
                Spacer(Modifier.height(8.dp))
                SelectedImageItem(
                    image = img,
                    onRemove = { onRemove(img) },
                    onClick = { onPreview(img) }
                )
            }
        }
    }
}

/**
 * 单个已选图片项：预览图 + 取景状态 + 右上角删除按钮，点击整项打开预览
 */
@Composable
private fun SelectedImageItem(
    image: SelectedImage,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val bitmap = remember(image.uri) {
        decodeSampledBitmap(image.uri, 128)
    }

    Card(
        modifier = Modifier
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
                    text = if (image.crop != null) "已设置取景框 · 点击预览" else "未设置取景框（默认居中）· 点击预览",
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
 * 取景框编辑器弹窗：
 * - 图片保持原始比例显示（等比缩放居中，留黑边，不裁剪）
 * - 中央虚线取景框 = 本机真实分辨率（横屏）比例，框外调暗
 * - 拖动移动取景框；左下角"自由取景"切换缩放模式
 */
@Composable
private fun ViewfinderDialog(
    image: SelectedImage,
    onDismiss: () -> Unit,
    onConfirm: (CropParams) -> Unit
) {
    val context = LocalContext.current
    val screen = remember { getScreenSize(context) }

    val src = remember(image.uri) {
        decodeSampledBitmap(image.uri, 2048)
    }

    val initial = image.crop ?: CropParams(0.5f, 0.5f, 1f)
    var cx by remember { mutableStateOf(initial.cx) }
    var cy by remember { mutableStateOf(initial.cy) }
    var w by remember { mutableStateOf(initial.w) }
    var freeMode by remember { mutableStateOf(false) }

    // 框高/框宽 = 本机真实分辨率横屏比例
    val ratio = screen.shortSide.toFloat() / screen.longSide.toFloat()
    val show = remember { mutableStateOf(true) }

    fun clampFrame() {
        w = w.coerceIn(0.15f, 1f)
        val h = w * ratio
        cx = cx.coerceIn(w / 2f, 1f - w / 2f)
        cy = cy.coerceIn(h / 2f, 1f - h / 2f)
    }

    SuperDialog(
        show = show,
        title = "预览",
        onDismissRequest = { show.value = false; onDismiss() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "虚线框为本机分辨率 ${screen.longSide}×${screen.shortSide}（横屏）可见区域，框外调暗；拖动调整",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                if (src != null) {
                    val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 280.dp.toPx() }
                    val fit = remember(src) { computeFitRect(src.width, src.height) }
                    Canvas(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .pointerInput(freeMode, ratio) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (freeMode) {
                                        w += drag.x / sidePx
                                    } else {
                                        cx += drag.x / sidePx
                                        cy += drag.y / sidePx
                                    }
                                    clampFrame()
                                }
                            }
                    ) {
                        val side = size.width
                        // 图片原比例居中绘制（不裁剪不变形）
                        drawImage(
                            image = src.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(src.width, src.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                (fit.left * side).toInt(),
                                (fit.top * side).toInt()
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (fit.width() * side).toInt(),
                                (fit.height() * side).toInt()
                            ),
                        )
                        // 取景框（本机真实分辨率横屏比例）
                        val fw = w * side
                        val fh = fw * ratio
                        val left = (cx * side) - fw / 2f
                        val top = (cy * side) - fh / 2f
                        // 框外调暗（四块）
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, size = androidx.compose.ui.geometry.Size(side, top.coerceAtLeast(0f)))
                        drawRect(
                            dim,
                            topLeft = Offset(0f, top + fh),
                            size = androidx.compose.ui.geometry.Size(side, side - (top + fh))
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(0f, top.coerceAtLeast(0f)),
                            size = androidx.compose.ui.geometry.Size(left.coerceAtLeast(0f), fh)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(left + fw, top.coerceAtLeast(0f)),
                            size = androidx.compose.ui.geometry.Size(side - (left + fw), fh)
                        )
                        // 虚线框
                        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(fw, fh),
                            style = Stroke(width = 2.5f, pathEffect = dash)
                        )
                    }
                } else {
                    Text("图片加载失败", color = colorScheme.onSurfaceVariantSummary)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (freeMode) "自由取景中：左右拖动缩放取景框" else "取景模式：拖动移动取景框",
                    fontSize = 12.sp,
                    color = if (freeMode) colorScheme.primary else colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = if (freeMode) "完成取景" else "自由取景",
                        onClick = {
                            freeMode = !freeMode
                            if (!freeMode) clampFrame()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { show.value = false; onDismiss() }
                        )
                        TextButton(
                            text = "确定",
                            onClick = {
                                clampFrame()
                                show.value = false
                                onConfirm(CropParams(cx, cy, w))
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
                text = "制作成文件",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "按记录的游戏文件数复制所选图片并逐个命名，保存到 luoxi/文件输出/",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 清理缓存（制作文件按钮左边）：清空文件输出目录
                TextButton(
                    text = if (clearing) "清理中…" else "清理缓存",
                    enabled = !clearing && !making,
                    onClick = {
                        scope.launch {
                            clearing = true
                            val ok = FileManagerUtils.clearOutputDir()
                            clearing = false
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已清空文件输出目录" else "清理失败，请检查权限",
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
                            val ok = makeFiles(context, images) { step ->
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
                            text = "知道了",
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
 * 1. 读记录的游戏文件名 N 个；2. 图片 M 张均分（各 N/M 份，余数随机多一份）；
 * 3. 渲染到中转目录（app 外部目录，shell 可访问）；
 * 4. shell 移入 文件输出/（先清空旧输出）。
 */
private suspend fun makeFiles(
    context: android.content.Context,
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

    val screen = getScreenSize(context)
    val ratio = screen.shortSide.toFloat() / screen.longSide.toFloat()
    val staging = java.io.File(FileManagerUtils.workDir(), "make").apply {
        mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
    }

    val bitmapCache = mutableMapOf<Uri, Bitmap>()
    try {
        var done = 0
        assign.forEach { (img, name) ->
            done++
            onStep("正在生成文件 $done/${assign.size}")
            val srcBmp = bitmapCache.getOrPut(img.uri) {
                decodeSampledBitmap(img.uri, 2048) ?: return@withContext false
            }
            val crop = img.crop ?: CropParams(0.5f, 0.5f, 1f)
            val rendered = renderOutput(srcBmp, screen.longSide, crop, ratio)
            val f = File(staging, name)
            runCatching {
                java.io.FileOutputStream(f).use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }.onFailure { return@withContext false }
            if (rendered !== srcBmp) rendered.recycle()
        }
    } finally {
        bitmapCache.values.forEach { runCatching { it.recycle() } }
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
                text = "替换游戏文件",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "将删除游戏目录内的文件，替换为制作好的文件",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "替换文件",
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
                    text = "替换会先清空游戏目录内的加载图文件。选择\"备份\"会把原文件打包保存到 luoxi/备份/ 后再替换。",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(text = "取消", onClick = { confirmShow.value = false })
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    TextButton(
                        text = "不备份",
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
                        text = "备份",
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

private fun startReplace(
    scope: kotlinx.coroutines.CoroutineScope,
    withBackup: Boolean,
    context: android.content.Context,
    onUpdate: (String, Boolean) -> Unit
) {
    scope.launch {
        onUpdate("准备中…", true)
        val ok = FileManagerUtils.replaceGameFiles(withBackup) { step ->
            withContext(Dispatchers.Main) { onUpdate(step, true) }
        }
        withContext(Dispatchers.Main) {
            onUpdate(if (ok) "替换完成" else "替换失败", false)
            android.widget.Toast.makeText(
                context,
                if (ok) "替换完成" else "替换失败，请检查权限/先制作文件",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

// ---------- 图片处理 ----------

/**
 * 图片在正方形画布内的显示区（保持原比例、居中，归一化 0..1）。
 * 竖图上下占满左右留黑；横图左右占满上下留黑。不裁剪、不变形。
 */
private fun computeFitRect(imgW: Int, imgH: Int): RectF {
    return if (imgW >= imgH) {
        val h = imgH.toFloat() / imgW
        RectF(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    } else {
        val w = imgW.toFloat() / imgH
        RectF((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    }
}

/**
 * 渲染输出图（所见即所得）：
 * - 正方形画布，边长 = 屏幕长边（游戏加载图为正方形，中央横条为屏幕可见区）
 * - 图片按原比例居中绘制（黑边填充，不裁剪不变形）
 * - 取景框外区域压暗（与预览一致）
 */
private fun renderOutput(src: Bitmap, longSide: Int, crop: CropParams, ratio: Float): Bitmap {
    val out = Bitmap.createBitmap(longSide, longSide, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)

    // 图片原比例居中
    val fit = computeFitRect(src.width, src.height)
    val dst = Rect(
        (fit.left * longSide).toInt(),
        (fit.top * longSide).toInt(),
        (fit.right * longSide).toInt(),
        (fit.bottom * longSide).toInt()
    )
    canvas.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

    // 取景框外压暗（与预览效果一致）
    val fw = (crop.w.coerceIn(0.15f, 1f) * longSide)
    val fh = fw * ratio
    val left = (crop.cx * longSide) - fw / 2f
    val top = (crop.cy * longSide) - fh / 2f
    val dim = Paint().apply { color = android.graphics.Color.argb(140, 0, 0, 0) }
    // 上/下/左/右
    canvas.drawRect(0f, 0f, longSide.toFloat(), top.coerceAtLeast(0f), dim)
    canvas.drawRect(0f, top + fh, longSide.toFloat(), longSide.toFloat(), dim)
    canvas.drawRect(0f, top, left.coerceAtLeast(0f), top + fh, dim)
    canvas.drawRect(left + fw, top, longSide.toFloat(), top + fh, dim)
    return out
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
