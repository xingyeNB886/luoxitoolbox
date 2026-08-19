package com.sukisu.ultra.ui.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.sukisu.ultra.BuildConfig
import java.security.MessageDigest

/**
 * 洛茜工具箱 - APK 签名校验
 *
 * 防止 APK 被二次打包/重签名。
 * 运行时获取 APK 签名证书的 SHA-256，与构建时注入的预期值比对。
 * 不匹配则视为篡改，弹出警告。
 *
 * EXPECTED_SHA256 在 Gradle 构建时从实际使用的 keystore 动态计算，
 * 注入到 BuildConfig 中，确保与签名证书始终一致。
 */
object SignatureVerifier {

    /**
     * 预期签名证书 SHA-256（构建时从 keystore 动态计算注入）
     * 空字符串表示无 keystore 的 debug 构建，跳过校验。
     */
    private val EXPECTED_SHA256: String = BuildConfig.EXPECTED_SHA256

    /**
     * 校验结果
     */
    data class VerifyResult(
        val isValid: Boolean,
        val actualHash: String,
        val expectedHash: String
    )

    /**
     * 获取当前 APK 的签名证书 SHA-256，与预期值比对
     */
    fun verify(context: Context): VerifyResult {
        // 无 keystore 的 debug 构建（EXPECTED_SHA256 为空）→ 跳过校验
        if (EXPECTED_SHA256.isEmpty()) {
            return VerifyResult(
                isValid = true,
                actualHash = "",
                expectedHash = ""
            )
        }

        val actualHash = try {
            getSignatureSha256(context)
        } catch (e: Exception) {
            ""
        }
        return VerifyResult(
            isValid = actualHash.equals(EXPECTED_SHA256, ignoreCase = true),
            actualHash = actualHash,
            expectedHash = EXPECTED_SHA256
        )
    }

    /**
     * 从 PackageManager 获取签名并计算 SHA-256
     */
    private fun getSignatureSha256(context: Context): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }

        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }

        if (signatures.isEmpty()) return ""

        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(signatures[0].toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
