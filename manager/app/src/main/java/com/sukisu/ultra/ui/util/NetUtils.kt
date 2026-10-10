package com.sukisu.ultra.ui.util

import com.sukisu.ultra.ksuApp
import okhttp3.Request
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 通用网络下载：部分站点（如 luoxi.42web.io）带 JS 反爬（aes.js 挑战），
 * 首次请求返回的是要求执行 slowAES 解密、设置 __test cookie 后再跳转的 HTML。
 * 这里解析挑战、算出 cookie 后带 cookie 重试，从而拿到真实内容。
 */
object NetUtils {

    /** 下载 URL 的原始字节（自动过反爬挑战）。 */
    fun fetchBytes(url: String): ByteArray {
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
