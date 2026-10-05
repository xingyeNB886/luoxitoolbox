package me.weishu.kernelsu.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 应用背景图：自定义背景存于 filesDir/custom_background.jpg；
 * 无自定义时下载默认背景图（默认图站点带 JS 反爬，需先过挑战再下载）。
 */
object BackgroundUtils {

    private const val DEFAULT_URL =
        "https://luoxi.42web.io/down.php/c960893f0266e564cdf654100ef9ebc5.jpg"

    fun backgroundFile(context: Context): File = File(context.filesDir, "custom_background.jpg")

    fun hasBackground(context: Context): Boolean =
        backgroundFile(context).let { it.exists() && it.length() > 0L }

    fun loadBitmap(context: Context): Bitmap? = runCatching {
        val f = backgroundFile(context)
        if (!f.exists() || f.length() == 0L) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    fun saveBackground(context: Context, bitmap: Bitmap): Boolean = runCatching {
        FileOutputStream(backgroundFile(context)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        true
    }.getOrDefault(false)

    fun clearBackground(context: Context): Boolean = runCatching {
        backgroundFile(context).delete()
    }.getOrDefault(false)

    /** 无自定义背景时下载默认背景（幂等）。 */
    suspend fun ensureDefaultBackground(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (hasBackground(context)) return@withContext true
        val bytes = runCatching { fetchBytes(DEFAULT_URL) }.getOrNull() ?: return@withContext false
        if (bytes.isEmpty()) return@withContext false
        runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
            saveBackground(context, bmp)
        }.getOrDefault(false)
    }

    // ---------- 下载（含 JS 反爬挑战处理，与安全格机下载同一套逻辑） ----------

    private fun fetchBytes(url: String): ByteArray {
        val first = fetch(url, null)
        val text = String(first, Charsets.ISO_8859_1)
        if (text.contains("slowAES") && text.contains("__test")) {
            val test = solveChallenge(text) ?: throw IOException("challenge solve failed")
            val retryUrl = if (url.contains("?")) "$url&i=1" else "$url?i=1"
            return fetch(retryUrl, "__test=$test")
        }
        return first
    }

    private fun fetch(url: String, cookie: String?): ByteArray {
        val builder = Request.Builder().url(url)
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
        return ksuApp.okhttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("empty body")
        }
    }

    private fun solveChallenge(html: String): String? = runCatching {
        val vals = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        if (vals.size < 3) return null
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(vals[0]), "AES"), IvParameterSpec(hexToBytes(vals[1])))
        cipher.doFinal(hexToBytes(vals[2])).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
