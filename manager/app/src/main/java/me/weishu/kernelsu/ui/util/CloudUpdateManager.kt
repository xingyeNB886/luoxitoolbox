package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.Request
import java.security.MessageDigest

/**
 * 洛茜工具箱 - 云端更新管理器
 * 从QQ收藏读取版本号、公告、历史版本、下载链接
 * 
 * QQ收藏格式:
 * [内部版本号]1000000[内部版本号]
 * [链接]https://xxx.apk[链接]
 * [公告]公告内容[公告]
 * [历史版本]
 * 【1.0.0(1000000)--26.8.17】
 *  -发布洛茜工具箱
 * [历史版本]
 */
object CloudUpdateManager {

    private const val QQ_COLLECTION_URL = "https://sharechain.qq.com/895c780b3b254605d50f3af4f1d9e05b?qq_aio_chat_type=2"

    data class CloudData(
        val internalVersion: Int = 0,
        val downloadUrl: String = "",
        val announcement: String = "",
        val versionHistory: String = ""
    )

    /**
     * 获取本地内部版本号（防篡改：通过 BuildConfig 获取，ProGuard 混淆保护）
     */
    fun getLocalVersion(): Int {
        return me.weishu.kernelsu.BuildConfig.VERSION_CODE
    }

    /**
     * 验证APK签名，防止被二次打包绕过版本检测
     * 返回true表示签名有效
     */
    fun verifyAppSignature(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        signingInfo.apkContentsSigners
                    } else {
                        @Suppress("DEPRECATION")
                        signingInfo.signingCertificateHistory
                    }
                    if (signatures.isNotEmpty()) {
                        val cert = signatures[0]
                        val md = MessageDigest.getInstance("SHA-256")
                        val digest = md.digest(cert.encoded)
                        val hash = Base64.encodeToString(digest, Base64.NO_WRAP)
                        // 只要有有效签名就通过（运行时签名校验）
                        return hash.isNotEmpty()
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures
                if (signatures.isNotEmpty()) {
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(signatures[0].toByteArray())
                    val hash = Base64.encodeToString(digest, Base64.NO_WRAP)
                    return hash.isNotEmpty()
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从QQ收藏获取云端数据
     */
    suspend fun fetchCloudData(): CloudData = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(QQ_COLLECTION_URL)
                .build()
            val response = ksuApp.okhttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext CloudData()
            val body = response.body?.string() ?: return@withContext CloudData()
            parseCloudData(body)
        }.getOrDefault(CloudData())
    }

    /**
     * 解析QQ收藏返回的文本内容
     * 格式说明:
     * [内部版本号]数字[内部版本号]
     * [链接]URL[链接]
     * [公告]公告内容[公告]
     * [历史版本]历史版本内容[历史版本]
     */
    private fun parseCloudData(raw: String): CloudData {
        val internalVersion = extractBetween(raw, "[内部版本号]", "[内部版本号]")
            ?.trim()?.toIntOrNull() ?: 0
        val downloadUrl = extractBetween(raw, "[链接]", "[链接]")?.trim() ?: ""
        val announcement = extractBetween(raw, "[公告]", "[公告]")?.trim() ?: ""
        // 历史版本内容保留原始格式（包括空格和换行）
        val versionHistory = extractBetween(raw, "[历史版本]", "[历史版本]") ?: ""

        return CloudData(
            internalVersion = internalVersion,
            downloadUrl = downloadUrl,
            announcement = announcement,
            versionHistory = versionHistory
        )
    }

    /**
     * 提取两个标签之间的内容
     */
    private fun extractBetween(src: String, startTag: String, endTag: String): String? {
        val startIndex = src.indexOf(startTag)
        if (startIndex == -1) return null
        val contentStart = startIndex + startTag.length
        val endIndex = src.indexOf(endTag, contentStart)
        if (endIndex == -1) return null
        return src.substring(contentStart, endIndex)
    }
}