package com.sukisu.ultra.ui.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.CropInfo
import com.sukisu.ultra.ui.util.cropBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// 拖动模式
private const val DRAG_MOVE = 0
private const val DRAG_TL = 1
private const val DRAG_TR = 2
private const val DRAG_BL = 3
private const val DRAG_BR = 4

/**
 * 洛茜工具箱 · 自定义背景裁剪对话框（竖向版）
 *
 * 规则（所见即所得）：
 * 1. 图片以 Fit 居中显示，裁剪框的所有活动范围严格限制在【图片实际显示区域】内，
 *    任何一部分都不会跑到图片外面；
 * 2. 裁剪框比例锁定为手机竖屏屏幕比例，四角拖动只能等比缩放；
 * 3. 拖动中间移动裁剪框，边缘限位；
 * 4. 确认后按裁剪框在图片内的位置精确裁剪输出，无偏差。
 */
@Composable
fun ImageEditorDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 屏幕竖向宽高比（宽/高，竖屏 < 1）
    val displayMetrics = context.resources.displayMetrics
    val screenW = displayMetrics.widthPixels.toFloat()
    val screenH = displayMetrics.heightPixels.toFloat()
    val cropRatio = screenW / screenH

    // 对话框容器尺寸
    var displaySize by remember { mutableStateOf(Size.Zero) }

    // 原图尺寸（像素）
    var imageSize by remember { mutableStateOf(Size.Zero) }

    // 读取原图尺寸（不解码全图）
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(imageUri)?.use { ins ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(ins, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        imageSize = Size(opts.outWidth.toFloat(), opts.outHeight.toFloat())
                    }
                }
            }
        }
    }

    // 图片实际显示区域（ContentScale.Fit 居中，可能带黑边）
    val imageRect: Rect? = remember(displaySize, imageSize) {
        if (displaySize == Size.Zero || imageSize == Size.Zero) null
        else {
            val scale = min(
                displaySize.width / imageSize.width,
                displaySize.height / imageSize.height
            )
            val w = imageSize.width * scale
            val h = imageSize.height * scale
            Rect(
                left = (displaySize.width - w) / 2f,
                top = (displaySize.height - h) / 2f,
                right = (displaySize.width + w) / 2f,
                bottom = (displaySize.height + h) / 2f
            )
        }
    }

    // 裁剪框：屏幕绝对像素坐标（cropX/cropY 为左上角）
    var cropX by remember { mutableFloatStateOf(0f) }
    var cropY by remember { mutableFloatStateOf(0f) }
    var cropW by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    // 初始化：图片区域内能放下的最大等比框，居中
    LaunchedEffect(imageRect) {
        val r = imageRect ?: return@LaunchedEffect
        if (!initialized) {
            val maxW = min(r.width, r.height * cropRatio)
            cropW = maxW
            cropX = r.left + (r.width - maxW) / 2f
            cropY = r.top + (r.height - maxW / cropRatio) / 2f
            initialized = true
        }
    }

    var dragMode by remember { mutableIntStateOf(DRAG_MOVE) }
    // 手指按下时与目标点的偏移，避免跳变
    var grabDx by remember { mutableFloatStateOf(0f) }
    var grabDy by remember { mutableFloatStateOf(0f) }

    val minCropW = 60f          // 最小宽度（px）
    val cornerHitRadius = 100f  // 四角点击判定半径（px）

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size ->
                    displaySize = Size(size.width.toFloat(), size.height.toFloat())
                }
        ) {
            // 图片（Fit 居中显示）
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(false)
                    .build(),
                contentDescription = stringResource(R.string.settings_custom_background),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // 裁剪覆盖层（仅在图片区域与初始裁剪框就绪后显示）
            if (imageRect != null && initialized && cropW > 0f) {
                val r = imageRect
                val globalMaxW = min(r.width, r.height * cropRatio)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(r, cropRatio) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // 当前裁剪框四角
                                    val tlX = cropX; val tlY = cropY
                                    val trX = cropX + cropW; val trY = cropY
                                    val blX = cropX; val blY = cropY + cropW / cropRatio
                                    val brX = cropX + cropW; val brY = cropY + cropW / cropRatio

                                    // 距离最近的角
                                    val dTL = hypot(offset.x - tlX, offset.y - tlY)
                                    val dTR = hypot(offset.x - trX, offset.y - trY)
                                    val dBL = hypot(offset.x - blX, offset.y - blY)
                                    val dBR = hypot(offset.x - brX, offset.y - brY)
                                    val minD = min(min(dTL, dTR), min(dBL, dBR))

                                    dragMode = when (minD) {
                                        dTL -> DRAG_TL
                                        dTR -> DRAG_TR
                                        dBL -> DRAG_BL
                                        dBR -> DRAG_BR
                                        else -> DRAG_MOVE
                                    }
                                    if (minD > cornerHitRadius) dragMode = DRAG_MOVE

                                    // 记录按下点与对应锚点的偏移
                                    when (dragMode) {
                                        DRAG_TL -> { grabDx = offset.x - tlX; grabDy = offset.y - tlY }
                                        DRAG_TR -> { grabDx = offset.x - trX; grabDy = offset.y - trY }
                                        DRAG_BL -> { grabDx = offset.x - blX; grabDy = offset.y - blY }
                                        DRAG_BR -> { grabDx = offset.x - brX; grabDy = offset.y - brY }
                                        else -> { grabDx = offset.x - cropX; grabDy = offset.y - cropY }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pos = change.position

                                    when (dragMode) {
                                        DRAG_MOVE -> {
                                            // 移动：边缘限位，任何一边都不出图片
                                            cropX = (pos.x - grabDx).coerceIn(r.left, r.right - cropW)
                                            cropY = (pos.y - grabDy).coerceIn(r.top, r.bottom - cropW / cropRatio)
                                        }

                                        DRAG_BR -> {
                                            // 锚点 = 左上角
                                            val ax = cropX; val ay = cropY
                                            val tgtX = pos.x - grabDx
                                            val tgtY = pos.y - grabDy
                                            val projW = tgtX - ax
                                            val projWByY = (tgtY - ay) * cropRatio
                                            var newW = (projW + projWByY) / 2f
                                            newW = newW.coerceIn(
                                                minCropW,
                                                min(min(r.right - ax, (r.bottom - ay) * cropRatio), globalMaxW)
                                            )
                                            cropW = newW
                                        }

                                        DRAG_TL -> {
                                            // 锚点 = 右下角
                                            val ax = cropX + cropW; val ay = cropY + cropW / cropRatio
                                            val tgtX = pos.x - grabDx
                                            val tgtY = pos.y - grabDy
                                            val projW = ax - tgtX
                                            val projWByY = (ay - tgtY) * cropRatio
                                            var newW = (projW + projWByY) / 2f
                                            newW = newW.coerceIn(
                                                minCropW,
                                                min(min(ax - r.left, (ay - r.top) * cropRatio), globalMaxW)
                                            )
                                            cropW = newW
                                            cropX = ax - newW
                                            cropY = ay - newW / cropRatio
                                        }

                                        DRAG_TR -> {
                                            // 锚点 = 左下角
                                            val ax = cropX; val ay = cropY + cropW / cropRatio
                                            val tgtX = pos.x - grabDx
                                            val tgtY = pos.y - grabDy
                                            val projW = tgtX - ax
                                            val projWByY = (ay - tgtY) * cropRatio
                                            var newW = (projW + projWByY) / 2f
                                            newW = newW.coerceIn(
                                                minCropW,
                                                min(min(r.right - ax, (ay - r.top) * cropRatio), globalMaxW)
                                            )
                                            cropW = newW
                                            cropX = ax
                                            cropY = ay - newW / cropRatio
                                        }

                                        DRAG_BL -> {
                                            // 锚点 = 右上角
                                            val ax = cropX + cropW; val ay = cropY
                                            val tgtX = pos.x - grabDx
                                            val tgtY = pos.y - grabDy
                                            val projW = ax - tgtX
                                            val projWByY = (tgtY - ay) * cropRatio
                                            var newW = (projW + projWByY) / 2f
                                            newW = newW.coerceIn(
                                                minCropW,
                                                min(min(ax - r.left, (r.bottom - ay) * cropRatio), globalMaxW)
                                            )
                                            cropW = newW
                                            cropX = ax - newW
                                            cropY = ay
                                        }
                                    }
                                }
                            )
                        }
                        .drawWithContent {
                            drawContent()

                            val cX = cropX
                            val cY = cropY
                            val cW = cropW
                            val cH = cropW / cropRatio

                            // 图片区域外全黑（图片可能没填满对话框）
                            val dim = Color.Black.copy(alpha = 0.65f)
                            // 图片外的部分：上、下、左、右
                            drawRect(dim, Offset.Zero, Size(size.width, r.top))
                            drawRect(
                                dim,
                                Offset(0f, r.bottom),
                                Size(size.width, size.height - r.bottom)
                            )
                            drawRect(dim, Offset.Zero, Size(r.left, size.height))
                            drawRect(
                                dim,
                                Offset(r.right, 0f),
                                Size(size.width - r.right, size.height)
                            )
                            // 图片内、裁剪框外的部分压暗
                            drawRect(dim, Offset(r.left, r.top), Size(r.width, cY - r.top))
                            drawRect(
                                dim,
                                Offset(r.left, cY + cH),
                                Size(r.width, r.bottom - cY - cH)
                            )
                            drawRect(dim, Offset(r.left, cY), Size(cX - r.left, cH))
                            drawRect(
                                dim,
                                Offset(cX + cW, cY),
                                Size(r.right - cX - cW, cH)
                            )

                            // 裁剪框边框
                            val borderColor = Color.White
                            drawRect(
                                borderColor,
                                topLeft = Offset(cX, cY),
                                size = Size(cW, cH),
                                style = Stroke(width = 3f)
                            )

                            // 四角手柄
                            val cornerLen = 44f
                            val handleW = 7f
                            // 左上
                            drawRect(borderColor, Offset(cX, cY), Size(cornerLen, handleW))
                            drawRect(borderColor, Offset(cX, cY), Size(handleW, cornerLen))
                            // 右上
                            drawRect(borderColor, Offset(cX + cW - cornerLen, cY), Size(cornerLen, handleW))
                            drawRect(borderColor, Offset(cX + cW - handleW, cY), Size(handleW, cornerLen))
                            // 左下
                            drawRect(borderColor, Offset(cX, cY + cH - handleW), Size(cornerLen, handleW))
                            drawRect(borderColor, Offset(cX, cY + cH - cornerLen), Size(handleW, cornerLen))
                            // 右下
                            drawRect(borderColor, Offset(cX + cW - cornerLen, cY + cH - handleW), Size(cornerLen, handleW))
                            drawRect(borderColor, Offset(cX + cW - handleW, cY + cH - cornerLen), Size(handleW, cornerLen))
                        }
                )
            }

            // 顶部按钮栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = Color.White
                    )
                }

                Button(
                    onClick = {
                        val r = imageRect ?: return@Button
                        if (cropW <= 0f || imageSize == Size.Zero) return@Button
                        scope.launch {
                            runCatching {
                                // 屏幕坐标 → 原图像素坐标（精确映射，所见即所得）
                                val scaleX = imageSize.width / r.width
                                val scaleY = imageSize.height / r.height
                                val cH = cropW / cropRatio

                                val origX = ((cropX - r.left) * scaleX).roundToInt()
                                    .coerceIn(0, imageSize.width.toInt() - 1)
                                val origY = ((cropY - r.top) * scaleY).roundToInt()
                                    .coerceIn(0, imageSize.height.toInt() - 1)
                                val origW = (cropW * scaleX).roundToInt()
                                    .coerceIn(1, imageSize.width.toInt() - origX)
                                val origH = (cH * scaleY).roundToInt()
                                    .coerceIn(1, imageSize.height.toInt() - origY)

                                val cropInfo = CropInfo(
                                    normX = origX / imageSize.width,
                                    normY = origY / imageSize.height,
                                    normWidth = origW / imageSize.width,
                                    normHeight = origH / imageSize.height
                                )
                                val savedUri = withContext(Dispatchers.IO) {
                                    context.cropBackground(imageUri, cropInfo)
                                }
                                savedUri?.let { onConfirm(it) }
                            }.onFailure { it.printStackTrace() }
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.confirm),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.confirm),
                        color = Color.White
                    )
                }
            }

            // 底部提示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text(
                    text = "拖动裁剪框选择区域，拖四角等比缩放，点确认自动更换背景",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
