package com.sukisu.ultra.ui.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
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
 * 裁剪信息：归一化坐标 (0~1)
 */
data class CropInfo(
    val normX: Float,      // 裁剪框左上角 X (0~1)
    val normY: Float,      // 裁剪框左上角 Y (0~1)
    val normWidth: Float,  // 裁剪框宽度 (0~1)
    val normHeight: Float  // 裁剪框高度 (0~1)
)

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

    val displayMetrics = resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
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

/**
 * 根据裁剪信息从原图中精确裁剪
 * 所见即所得：裁剪框内的内容就是最终输出
 */
fun Context.cropBackground(uri: Uri, cropInfo: CropInfo): Uri? {
    try {
        val bitmap = getImageBitmap(uri) ?: return null
        val origWidth = bitmap.width
        val origHeight = bitmap.height

        // 将归一化坐标转换为像素坐标
        val cropX = (cropInfo.normX * origWidth).toInt().coerceIn(0, origWidth - 1)
        val cropY = (cropInfo.normY * origHeight).toInt().coerceIn(0, origHeight - 1)
        val cropW = (cropInfo.normWidth * origWidth).toInt().coerceIn(1, origWidth - cropX)
        val cropH = (cropInfo.normHeight * origHeight).toInt().coerceIn(1, origHeight - cropY)

        // 精确裁剪
        val croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

        val fileName = "custom_background_cropped.jpg"
        val file = File(filesDir, fileName)
        val outputStream = FileOutputStream(file)

        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        outputStream.flush()
        outputStream.close()

        return Uri.fromFile(file)
    } catch (e: Exception) {
        Log.e("BackgroundUtils", "Failed to crop image: ${e.message}", e)
        return null
    }
}
