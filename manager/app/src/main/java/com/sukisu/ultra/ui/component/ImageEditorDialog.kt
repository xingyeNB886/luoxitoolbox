package com.sukisu.ultra.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sukisu.ultra.R
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.getRealResolution
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 洛茜工具箱 · 自定义背景裁剪弹窗
 *
 * 从备份工具箱（加载图替换）的 CropDialog 原样照搬，仅把比例改为竖屏真实分辨率：
 * - 图片按原始比例完整显示（画布比例 = 图片比例）
 * - 裁剪框比例固定为本机竖屏真实分辨率比例（屏幕高/宽），框为竖屏（高>宽），不可更改
 * - 裁剪框内部原色不变，外部压暗
 * - 按住框内拖动 = 移动；按住四角任意一角拖动 = 等比例缩放（对角固定）
 * - 裁剪框始终被图片边界限制，不会超出图片
 * - 确定后直接裁剪，结果返回给调用方自动更换背景
 * - 裁剪框使用归一化坐标（0-1），消除 inSampleSize 取整导致的精度偏差
 */
@Composable
fun ImageEditorDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 竖屏真实分辨率，裁剪框比例 = 屏幕高/宽（大于 1，框即为竖屏「高>宽」）
    val (screenW, screenH) = remember { context.getRealResolution() }
    val cropRatio = screenH.toFloat() / screenW.toFloat()

    val src = remember(imageUri) { decodeSampledBitmap(imageUri, 2048) }
    // 归一化裁剪框（0-1），默认 = 图片内能放下的最大等比框，居中
    var normBox by remember(imageUri) {
        mutableStateOf(src?.let { defaultNormBox(it.width.toFloat(), it.height.toFloat(), cropRatio) })
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
                            text = stringResource(R.string.crop_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.IconButton(onClick = { if (!saving) onDismiss() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.crop_guide, "${screenW}×${screenH}"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))

                    if (src != null && normBox != null) {
                        val imgW = src.width.toFloat()
                        val imgH = src.height.toFloat()
                        val imgAspect = imgW / imgH

                        // 画布尺寸跟随图片比例（宽上限 320dp、高上限 380dp，竖屏适配）
                        val maxW = 320.dp
                        val maxH = 380.dp
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
                                    var mode = 0   // 0=无操作 1=移动框 2=缩放角
                                    var corner = 0 // 0=左上 1=右上 2=左下 3=右下
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            val nb = normBox ?: return@detectDragGestures
                                            val cwR = size.width.toFloat()
                                            val chR = size.height.toFloat()
                                            val s = cwR / imgW
                                            // 裁剪框四角在画布上的像素位置
                                            val bl = nb.left * imgW * s
                                            val bt = nb.top * imgH * s
                                            val br = nb.right * imgW * s
                                            val bb = nb.bottom * imgH * s
                                            val grab = with(density) { 26.dp.toPx() }
                                            val corners = arrayOf(
                                                Offset(bl, bt), Offset(br, bt),
                                                Offset(bl, bb), Offset(br, bb)
                                            )
                                            val hit = corners.indexOfFirst { (it - p).getDistance() <= grab }
                                            if (hit >= 0) {
                                                mode = 2; corner = hit
                                            } else if (p.x >= bl && p.x <= br && p.y >= bt && p.y <= bb) {
                                                mode = 1
                                            } else {
                                                mode = 0
                                            }
                                        },
                                        onDrag = { change, drag ->
                                            val nb = normBox ?: return@detectDragGestures
                                            val cwR = size.width.toFloat()
                                            val chR = size.height.toFloat()
                                            val s = cwR / imgW
                                            if (mode == 1) {
                                                change.consume()
                                                // 整体移动：归一化偏移，钳制在 [0, 1-尺寸]
                                                val dnX = drag.x / s / imgW
                                                val dnY = drag.y / s / imgH
                                                val nw = nb.right - nb.left
                                                val nh = nb.bottom - nb.top
                                                val nl = (nb.left + dnX).coerceIn(0f, 1f - nw)
                                                val nt = (nb.top + dnY).coerceIn(0f, 1f - nh)
                                                normBox = RectF(nl, nt, nl + nw, nt + nh)
                                            } else if (mode == 2) {
                                                change.consume()
                                                // 对角固定缩放：对角点不动，拖动点向拖动方向移动，保持比例
                                                val px = change.position.x / s
                                                val py = change.position.y / s
                                                val fixX = if (corner == 0 || corner == 2) nb.right * imgW else nb.left * imgW
                                                val fixY = if (corner == 0 || corner == 1) nb.bottom * imgH else nb.top * imgH
                                                val dx = px - fixX
                                                val dy = py - fixY
                                                val dirX = if (dx >= 0f) 1f else -1f
                                                val dirY = if (dy >= 0f) 1f else -1f
                                                val availX = if (dirX > 0f) imgW - fixX else fixX
                                                val availY = if (dirY > 0f) imgH - fixY else fixY
                                                val wMax = minOf(availX, availY / cropRatio, minOf(imgW, imgH / cropRatio))
                                                val wMin = minOf(24f, wMax)
                                                var w = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy) / cropRatio)
                                                w = w.coerceIn(wMin, wMax)
                                                val h = w * cropRatio
                                                val nl = (minOf(fixX, fixX + dirX * w) / imgW).coerceIn(0f, 1f)
                                                val nt = (minOf(fixY, fixY + dirY * h) / imgH).coerceIn(0f, 1f)
                                                val nr = (maxOf(fixX, fixX + dirX * w) / imgW).coerceIn(0f, 1f)
                                                val nb2 = (maxOf(fixY, fixY + dirY * h) / imgH).coerceIn(0f, 1f)
                                                normBox = RectF(nl, nt, nr, nb2)
                                            }
                                        }
                                    )
                                }
                        ) {
                            val cw = size.width
                            val ch = size.height
                            val cwR = cw.roundToInt().toFloat()
                            val chR = ch.roundToInt().toFloat()
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
                            text = stringResource(R.string.image_load_failed),
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
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(
                                text = stringResource(R.string.cropping),
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
                            text = stringResource(R.string.crop_confirm_content),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { if (!saving) onDismiss() },
                                enabled = !saving
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                            Button(
                                onClick = {
                                    val nb = normBox ?: return@Button
                                    scope.launch {
                                        saving = true
                                        val newUri = withContext(Dispatchers.IO) {
                                            performCrop(imageUri, nb, cropRatio)
                                        }
                                        saving = false
                                        if (newUri != null) {
                                            onDismiss()
                                            onConfirm(newUri)
                                        }
                                    }
                                },
                                enabled = !saving && normBox != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(stringResource(R.string.confirm))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 默认归一化裁剪框：图片内能放下的最大等比框的 80%（比例 = 本机竖屏真实分辨率），居中。
 * 缩小默认框以保证四周都有移动空间，可拖动到图片任意角落再放大。
 * 返回归一化坐标（0-1）。
 */
private fun defaultNormBox(imgW: Float, imgH: Float, ratio: Float): RectF {
    val w = minOf(imgW, imgH / ratio) * 0.8f
    val h = w * ratio
    val l = (imgW - w) / 2f
    val t = (imgH - h) / 2f
    return RectF(l / imgW, t / imgH, (l + w) / imgW, (t + h) / imgH)
}

/**
 * 执行裁剪：使用归一化坐标直接计算裁剪区域，消除 inSampleSize 取整导致的偏差。
 * 以高分辨率（≤4096）重新解码原图，按归一化框裁剪，结果存为本地 JPEG 文件。
 * @return 裁剪结果文件 uri；失败返回 null
 */
private fun performCrop(
    uri: Uri,
    normBox: RectF,
    cropRatio: Float
): Uri? {
    return try {
        val full = decodeSampledBitmap(uri, 4096) ?: return null
        val fw = full.width.toFloat()
        val fh = full.height.toFloat()
        val normW = normBox.right - normBox.left
        val boxW = normW * fw
        val boxH = boxW * cropRatio
        val cx = (normBox.left + normBox.right) / 2f * fw
        val cy = (normBox.top + normBox.bottom) / 2f * fh
        val l = (cx - boxW / 2f).roundToInt().coerceIn(0, full.width - 1)
        val t = (cy - boxH / 2f).roundToInt().coerceIn(0, full.height - 1)
        val r = (cx + boxW / 2f).roundToInt().coerceIn(l + 1, full.width)
        val b = (cy + boxH / 2f).roundToInt().coerceIn(t + 1, full.height)
        val cropped = Bitmap.createBitmap(full, l, t, r - l, b - t)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val f = File(ksuApp.filesDir, "custom_background_crop_$stamp.jpg")
        FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (cropped !== full) cropped.recycle()
        full.recycle()
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
