package me.weishu.kernelsu.ui.util

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
 * 安全格机脚本下载：直接下载到应用私有目录，并按内部版本号命名为 <version>.sh。
 *
 * 部分下载站（如 luoxi.42web.io）带 JS 反爬（aes.js 挑战）：首次请求返回的是一段
 * 要求执行 slowAES 解密、设置 __test cookie 后再跳转的 HTML，而非真实文件。
 * 这里在 App 端解析该挑战、算出 cookie 后带 cookie 重新请求，从而拿到真实文件。
 */
object SafeFormatManager {

    suspend fun downloadToPrivateDir(
        url: String,
        version: Int,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            FileManagerUtils.SAFE_FORMAT_DIR.mkdirs()
            val target = File(FileManagerUtils.SAFE_FORMAT_DIR, "$version.sh")
            val tmp = File(FileManagerUtils.SAFE_FORMAT_DIR, "$version.sh.download")
            runCatching { tmp.delete() }

            val bytes = fetchFileBytes(url)
            onProgress(100)
            FileOutputStream(tmp).use { it.write(bytes) }

            runCatching { target.delete() }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            if (!target.exists() || target.length() == 0L) {
                runCatching { target.delete() }
                null
            } else {
                target
            }
        }.getOrNull()
    }

    /** 请求文件；若命中 JS 反爬挑战，则解出 cookie 后重试。 */
    private fun fetchFileBytes(url: String): ByteArray {
        val firstBytes = fetchBytes(url, null)
        val text = String(firstBytes, Charsets.ISO_8859_1)
        if (text.contains("slowAES") && text.contains("__test")) {
            val test = solveChallenge(text) ?: throw IOException("challenge solve failed")
            val retryUrl = if (url.contains("?")) "$url&i=1" else "$url?i=1"
            val secondBytes = fetchBytes(retryUrl, "__test=$test")
            val secondText = String(secondBytes, Charsets.ISO_8859_1)
            if (secondText.contains("slowAES") && secondText.contains("__test")) {
                throw IOException("challenge still returned")
            }
            return secondBytes
        }
        return firstBytes
    }

    private fun fetchBytes(url: String, cookie: String?): ByteArray {
        val builder = Request.Builder().url(url)
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
        return ksuApp.okhttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("empty body")
        }
    }

    /**
     * 解析 aes.js 挑战：取三个 toNumbers 值（key / iv / cipher，均为 16 字节），
     * 用 AES-CBC-NoPadding 解密后转十六进制，即为 __test 的值。
     */
    private fun solveChallenge(html: String): String? = runCatching {
        val vals = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        if (vals.size < 3) return null
        val key = hexToBytes(vals[0])
        val iv = hexToBytes(vals[1])
        val ct = hexToBytes(vals[2])
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ct)
        plain.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
