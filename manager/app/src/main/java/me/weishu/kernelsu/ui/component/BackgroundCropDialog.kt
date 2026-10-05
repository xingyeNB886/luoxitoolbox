package me.weishu.kernelsu.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.getScreenSize
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 背景图裁剪弹窗：与加载图裁剪同一套交互，仅把裁剪框比例改成竖向（高/宽 = 本机长/短边）。
 * 确定后返回裁剪结果 Bitmap 给调用方保存。
 */
@Composable
fun BackgroundCropDialog(
    uri: Uri,
    onCropped: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = remember { getScreenSize(context) }
    // 竖向比例：长边 / 短边（高 / 宽）
    val cropRatio = screen.longSide.toFloat() / screen.shortSide.toFloat()

    val src = remember(uri) { decodeSampledBitmap(uri, 2048) }
    var normBox by remember(uri) {
        mutableStateOf(src?.let { defaultNormBox(it.width.toFloat(), it.height.toFloat(), cropRatio) })
    }
    var saving by remember { mutableStateOf(false) }
    val show = remember { mutableStateOf(true) }
    val density = LocalDensity.current

    SuperDialog(
        show = show,
        title = "裁剪背景图",
        onDismissRequest = { if (!saving) { show.value = false; onDismiss() } },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.bg_crop_guide, "${screen.longSide}×${screen.shortSide}"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                if (src != null && normBox != null) {
                    val imgW = src.width.toFloat()
                    val imgH = src.height.toFloat()
                    val imgAspect = imgW / imgH

                    val maxW = 280.dp
                    val maxH = 320.dp
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
                                var mode = 0
                                var corner = 0
                                detectDragGestures(
                                    onDragStart = { p ->
                                        val nb = normBox ?: return@detectDragGestures
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
                                        change.consume()
                                        val nb = normBox ?: return@detectDragGestures
                                        val cwR = size.width.toFloat()
                                        val chR = size.height.toFloat()
                                        val s = cwR / imgW
                                        if (mode == 1) {
                                            val dnX = drag.x / s / imgW
                                            val dnY = drag.y / s / imgH
                                            val nw = nb.right - nb.left
                                            val nh = nb.bottom - nb.top
                                            val nl = (nb.left + dnX).coerceIn(0f, 1f - nw)
                                            val nt = (nb.top + dnY).coerceIn(0f, 1f - nh)
                                            normBox = RectF(nl, nt, nl + nw, nt + nh)
                                        } else if (mode == 2) {
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
                        val cwR = size.width.roundToInt().toFloat()
                        val chR = size.height.roundToInt().toFloat()
                        val s = cwR / imgW
                        drawImage(
                            image = src.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(src.width, src.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(cwR.toInt(), chR.toInt()),
                        )
                        val nb = normBox!!
                        val bl = nb.left * imgW * s; val bt = nb.top * imgH * s
                        val br = nb.right * imgW * s; val bb = nb.bottom * imgH * s
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, size = androidx.compose.ui.geometry.Size(cwR, bt))
                        drawRect(dim, topLeft = Offset(0f, bb), size = androidx.compose.ui.geometry.Size(cwR, chR - bb))
                        drawRect(dim, topLeft = Offset(0f, bt), size = androidx.compose.ui.geometry.Size(bl, bb - bt))
                        drawRect(dim, topLeft = Offset(br, bt), size = androidx.compose.ui.geometry.Size(cwR - br, bb - bt))
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(bl, bt),
                            size = androidx.compose.ui.geometry.Size(br - bl, bb - bt),
                            style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                        )
                        val hr = 6.dp.toPx()
                        listOf(Offset(bl, bt), Offset(br, bt), Offset(bl, bb), Offset(br, bb)).forEach { c ->
                            drawCircle(Color.White, radius = hr, center = c)
                            drawCircle(Color.Black.copy(alpha = 0.35f), radius = hr, center = c, style = Stroke(2f))
                        }
                    }
                } else {
                    Text("图片加载失败", color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        enabled = !saving,
                        onClick = { show.value = false; onDismiss() }
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        text = stringResource(R.string.confirm),
                        enabled = !saving && normBox != null,
                        onClick = {
                            val nb = normBox ?: return@TextButton
                            scope.launch {
                                saving = true
                                val bmp = withContext(Dispatchers.IO) { cropImage(uri, nb, cropRatio) }
                                saving = false
                                if (bmp != null) {
                                    show.value = false
                                    onCropped(bmp)
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
    )
}

/** 默认归一化裁剪框：图片内能放下的最大等比框（比例 = 竖向长/短边），居中。 */
private fun defaultNormBox(imgW: Float, imgH: Float, ratio: Float): RectF {
    val w = minOf(imgW, imgH / ratio)
    val h = w * ratio
    val l = (imgW - w) / 2f
    val t = (imgH - h) / 2f
    return RectF(l / imgW, t / imgH, (l + w) / imgW, (t + h) / imgH)
}

/** 按归一化框与竖向比例裁剪出 Bitmap。 */
private fun cropImage(uri: Uri, normBox: RectF, cropRatio: Float): Bitmap? {
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
        Bitmap.createBitmap(full, l, t, r - l, b - t)
    } catch (e: Exception) {
        null
    }
}

/** 解码 URI 图片为采样位图（保持原始比例，仅降采样防 OOM）。 */
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
