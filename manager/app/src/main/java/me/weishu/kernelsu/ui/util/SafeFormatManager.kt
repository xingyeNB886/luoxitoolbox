package me.weishu.kernelsu.ui.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 安全格机脚本下载：直接下载到应用私有目录，并按内部版本号命名为 <version>.sh。
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

            ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    var soFar = 0L
                    val source = body.byteStream()
                    while (true) {
                        read = source.read(buf)
                        if (read == -1) break
                        fos.write(buf, 0, read)
                        soFar += read
                        if (total > 0) {
                            onProgress(((soFar * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    fos.flush()
                }
            }

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
}
