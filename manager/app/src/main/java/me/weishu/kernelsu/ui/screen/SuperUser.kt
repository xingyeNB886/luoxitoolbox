package me.weishu.kernelsu.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.width
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
 * 取景框参数（全部归一化到正方形图的 0..1）
 *
 * @param cx 框中心 X
 * @param cy 框中心 Y
 * @param w  框宽（框高 = 框宽 × 屏幕短边/长边，保持横屏比例）
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

    // 已选图片列表（含每张图的取景框参数），仅内存缓存
    var images by remember { mutableStateOf(listOf<SelectedImage>()) }
    // 正在编辑取景框的图片
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

    // 取景框编辑器（点击已选图片打开）
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
 * 选择图片板块
 */
@Composable
private fun ImagePickerCard(
    images: List<SelectedImage>,
    onPick: (List<Uri>) -> Unit,
    onRemove: (SelectedImage) -> Unit,
    onPreview: (SelectedImage) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
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
                    onClick = {
                        launcher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
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
                    contentScale = ContentScale.Crop,
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
 * - 正方形图（边长 = 屏幕长边对应），中央虚线取景框（横屏屏幕比例），框外调暗
 * - 拖动移动取景框；左下角"自由取景"切换为缩放模式
 */
@Composable
private fun ViewfinderDialog(
    image: SelectedImage,
    onDismiss: () -> Unit,
    onConfirm: (CropParams) -> Unit
) {
    val context = LocalContext.current
    val screen = remember { getScreenSize(context) }

    // 编辑用正方形位图（原图 centerCrop，限尺寸防 OOM）
    val square = remember(image.uri) {
        loadSquareBitmap(image.uri, 2048)
    }

    val initial = image.crop ?: CropParams(0.5f, 0.5f, 1f)
    var cx by remember { mutableStateOf(initial.cx) }
    var cy by remember { mutableStateOf(initial.cy) }
    var w by remember { mutableStateOf(initial.w) }
    var freeMode by remember { mutableStateOf(false) }

    val ratio = screen.shortSide.toFloat() / screen.longSide.toFloat() // 框高/框宽
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
                    text = "虚线框为游戏内可见区域（横屏），框外部分会被调暗；拖动调整位置",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                if (square != null) {
                    val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 280.dp.toPx() }
                    Canvas(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .pointerInput(freeMode, ratio) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (freeMode) {
                                        // 自由取景：拖动缩放框宽（保持横屏比例）
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
                        // 正方形图
                        drawImage(
                            image = square.asImageBitmap(),
                            dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt())
                        )
                        val fw = w * side
                        val fh = fw * ratio
                        val left = (cx * side) - fw / 2f
                        val top = (cy * side) - fh / 2f
                        // 框外调暗（上/下/左/右四块）
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
                    // 左下角：自由取景开关
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
 * 读取伪装系统文件里记录的游戏文件名，把选中的图片平均分配改名为游戏文件名，
 * 输出到 /storage/emulated/0/luoxi/文件输出/
 */
@Composable
private fun MakeFilesCard(images: List<SelectedImage>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
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
                text = "读取已记录的游戏文件名，把选中的图片平均分配并改名为游戏文件名，保存到 luoxi/文件输出/",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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
 * 制作文件的实际逻辑：
 * 1. 读记录的游戏文件名 N 个；2. 图片 M 张均分（余数随机多分一张）；
 * 3. 每张图按取景框渲染成"边长=屏幕长边"的正方形图，改名为分到的游戏文件名；
 * 4. 全部写入 luoxi/文件输出/（先清空旧输出）
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
    // 随机挑 rem 张图各多分一个
    val extraIdx = imgs.indices.shuffled().take(rem).toSet()

    // 分配：图片索引 -> 分到的文件名列表
    val assign = mutableListOf<Pair<SelectedImage, String>>()
    var idx = 0
    imgs.forEachIndexed { i, img ->
        repeat(base + if (i in extraIdx) 1 else 0) {
            assign.add(img to names[idx++])
        }
    }

    val screen = getScreenSize(context)
    val outDir = File(context.cacheDir, "luoxi_make").apply {
        mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
    }

    // 同一张图可能分到多个文件名，解码一次复用
    val squareCache = mutableMapOf<Uri, Bitmap>()
    try {
        var done = 0
        assign.forEach { (img, name) ->
            done++
            onStep("正在生成文件 $done/${assign.size}")
            val square = squareCache.getOrPut(img.uri) {
                loadSquareBitmap(img.uri, 2048) ?: return@withContext false
            }
            val crop = img.crop ?: CropParams(0.5f, 0.5f, 1f)
            val rendered = renderOutput(square, screen.longSide, crop)
            val f = File(outDir, name)
            runCatching {
                java.io.FileOutputStream(f).use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }.onFailure { return@withContext false }
            if (rendered !== square) rendered.recycle()
        }
    } finally {
        squareCache.values.forEach { runCatching { it.recycle() } }
    }

    onStep("正在写入文件输出目录")
    // 清空旧输出再移入
    FileManagerUtils.exec("rm -rf '${FileManagerUtils.OUTPUT_DIR}'")
    val files = outDir.listFiles()?.toList() ?: emptyList()
    FileManagerUtils.moveFilesToDir(files, FileManagerUtils.OUTPUT_DIR)
        .also { files.forEach { r -> runCatching { r.delete() } } }
}

/**
 * 替换游戏文件板块：
 * 删除游戏目录（LoadingBG）内文件，把制作好的文件移进去；可选先备份。
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

    // 是否备份替换
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
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "不备份",
                        onClick = { confirmShow.value = false; startReplace(scope, false, context) { s, r ->
                            stepText = s; running = r
                            progressShow.value = r || stepText != ""
                        } }
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "备份",
                        onClick = { confirmShow.value = false; startReplace(scope, true, context) { s, r ->
                            stepText = s; running = r
                            progressShow.value = r || stepText != ""
                        } },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    // 实时进度
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
                if (ok) "替换完成" else "替换失败，请检查权限/制作文件",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

// ---------- 图片处理 ----------

/**
 * 解码 URI → centerCrop 正方形位图（限制最大边长，防 OOM）
 */
private fun loadSquareBitmap(uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // centerCrop 成正方形
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val sq = Bitmap.createBitmap(src, x, y, side, side)
        if (sq !== src) src.recycle()
        sq
    } catch (e: Exception) {
        null
    }
}

/**
 * 按取景框渲染输出图：
 * 以取景框中心为中心、边长=取景框宽 的正方形区域 → 缩放到屏幕长边 × 屏幕长边。
 * 游戏加载时取景框区域正好铺满横屏屏幕。
 */
private fun renderOutput(square: Bitmap, longSide: Int, crop: CropParams): Bitmap {
    val side = square.width
    var fw = (crop.w * side).toInt().coerceAtLeast(8)
    if (fw > side) fw = side
    val cxF = (crop.cx * side).toInt()
    val cyF = (crop.cy * side).toInt()
    val left = (cxF - fw / 2).coerceIn(0, side - fw)
    val top = (cyF - fw / 2).coerceIn(0, side - fw)
    val region = Bitmap.createBitmap(square, left, top, fw, fw)
    return Bitmap.createScaledBitmap(region, longSide, longSide, true)
}

/**
 * 解码 URI 图片为采样后的小图（仅用于列表预览，避免 OOM）
 */
private fun decodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize &&
            bounds.outHeight / (sample * 2) >= maxSize
        ) {
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
