package com.sukisu.ultra.ui.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import androidx.core.graphics.createBitmap

data class BackgroundTransformation(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

/**
 * 获取屏幕真实物理分辨率（含状态栏/导航栏，与实际显示一致）。
 *
 * resources.displayMetrics 在竖屏手机上会扣除系统栏（如 1080x2400 被报成 1080x2280），
 * 导致裁剪输出与实际背景有偏差 —— 所以这里必须用真实分辨率：
 *   - API 30+: WindowManager.maximumWindowMetrics（系统栏外完整屏幕）
 *   - API <30:  Display.getRealMetrics
 */
fun Context.getRealResolution(): Pair<Int, Int> {
    return try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val display: Display? = wm.defaultDisplay
            val out = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(out)
            if (out.widthPixels > 0 && out.heightPixels > 0) {
                Pair(out.widthPixels, out.heightPixels)
            } else {
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        }
    } catch (e: Exception) {
        val dm = resources.displayMetrics
        Pair(dm.widthPixels, dm.heightPixels)
    }
}

fun Context.getImageBitmap(uri: Uri): Bitmap? {
    return try {
        val contentResolver: ContentResolver = contentResolver
        val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        bitmap
    } catch (e: Exception) {
        Log.e("BackgroundUtils", "Failed to get image bitmap: ${e.message}")
        null
    }
}

fun Context.applyTransformationToBitmap(bitmap: Bitmap, transformation: BackgroundTransformation): Bitmap {
    val width = bitmap.width
    val height = bitmap.height

    // 用真实物理分辨率计算裁剪比例（竖屏手机为竖向比例）
    val (screenWidth, screenHeight) = getRealResolution()
    val screenRatio = screenHeight.toFloat() / screenWidth.toFloat()

    val targetWidth: Int
    val targetHeight: Int
    if (width.toFloat() / height.toFloat() > screenRatio) {
        targetHeight = height
        targetWidth = (height / screenRatio).toInt()
    } else {
        targetWidth = width
        targetHeight = (width * screenRatio).toInt()
    }

    val scaledBitmap = createBitmap(targetWidth, targetHeight)
    val canvas = Canvas(scaledBitmap)

    val matrix = Matrix()

    val safeScale = maxOf(0.1f, transformation.scale)
    matrix.postScale(safeScale, safeScale)

    val widthDiff = (bitmap.width * safeScale - targetWidth)
    val heightDiff = (bitmap.height * safeScale - targetHeight)

    val maxOffsetX = maxOf(0f, widthDiff / 2)
    val maxOffsetY = maxOf(0f, heightDiff / 2)

    val safeOffsetX = if (maxOffsetX > 0)
        transformation.offsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f
    val safeOffsetY = if (maxOffsetY > 0)
        transformation.offsetY.coerceIn(-maxOffsetY, maxOffsetY) else 0f

    val translationX = -widthDiff / 2 + safeOffsetX
    val translationY = -heightDiff / 2 + safeOffsetY

    matrix.postTranslate(translationX, translationY)

    canvas.drawBitmap(bitmap, matrix, null)

    return scaledBitmap
}

fun Context.saveTransformedBackground(uri: Uri, transformation: BackgroundTransformation): Uri? {
    try {
        val bitmap = getImageBitmap(uri) ?: return null
        val transformedBitmap = applyTransformationToBitmap(bitmap, transformation)

        val fileName = "custom_background_transformed.jpg"
        val file = File(filesDir, fileName)
        val outputStream = FileOutputStream(file)

        transformedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        outputStream.flush()
        outputStream.close()

        return Uri.fromFile(file)
    } catch (e: Exception) {
        Log.e("BackgroundUtils", "Failed to save transformed image: ${e.message}", e)
        return null
    }
}
