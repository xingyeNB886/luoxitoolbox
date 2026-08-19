package com.sukisu.ultra.ui.util

import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.AbstractTlsClient
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * 洛茜工具箱 · ADB 无线配对用的 BouncyCastle TLS 1.3 客户端。
 *
 * 之前的实现对 Android 隐藏 API（Conscrypt.exportKeyingMaterial）做反射取连接密钥，
 * 该接口在部分安卓版本 / 厂商 ROM 上不可用，导致"不适配安卓版本"类错误。
 * 这里改用 BouncyCastle（bctls）实现的 TLS 1.3，并直接从其对等上下文中导出
 * RFC 5705 keying material —— 与已经被真实 AOSP 设备验证过的开源实现
 * (Theodicean.SharpAdb) 方案一致，不依赖任何系统隐藏接口，跨版本行为稳定。
 *
 * 流程（与 AOSP adbd 配对服务一致）：
 *   1. 用 ADB RSA 密钥发起 TLS 1.3 握手（客户端证书 = ADB 证书，信任任何自签名服务端）
 *   2. 从 TLS 会话导出 keying material（label = "adb-label\0"，64 字节）
 *   3. 由调用方拼出配对密码 = 6 位配对码 + keying material，交给 SPAKE2 继续
 */
internal object WirelessAdbTls {

    /** AOSP adb 配对的导出 label（含结尾 NUL，与 adb 客户端一致） */
    private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
    internal const val EXPORT_KEY_SIZE = 64

    /** 一次 TLS 会话的产物：加密前后的字节流 + 导出的连接密钥 */
    internal class Session(
        val input: InputStream,
        val output: OutputStream,
        val keyingMaterial: ByteArray,
        private val protocol: TlsClientProtocol,
        private val socket: Socket,
    ) : Closeable {
        override fun close() {
            try {
                protocol.close()
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 在已连接的 [socket] 上完成 TLS 1.3 握手并导出 keying material。
     * [cert] 为 ADB 客户端证书，[rsaPrivateKey] 为对应的 RSA 私钥（原始编码）。
     *
     * @throws Exception 握手失败、版本不符、导出失败等
     */
    internal fun connect(socket: Socket, cert: X509Certificate, rsaPrivateKey: java.security.PrivateKey): Session {
        val rsaParams = PrivateKeyFactory.createKey(rsaPrivateKey.encoded) as RSAKeyParameters
        val client = AdbTlsClient(cert, rsaParams)
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        protocol.connect(client)

        // adbd 强制 TLS 1.3；若不是则说明设备端不符合预期
        if (client.negotiatedVersion != org.bouncycastle.tls.ProtocolVersion.TLSv13) {
            throw java.io.IOException("ADB 配对要求 TLS 1.3，实际协商为 ${client.negotiatedVersion}")
        }

        val keyMaterial = client.exportKeyingMaterial(EXPORTED_KEY_LABEL, EXPORT_KEY_SIZE)
        if (keyMaterial == null || keyMaterial.size < EXPORT_KEY_SIZE) {
            throw java.io.IOException("无法导出 TLS 密钥材料")
        }
        return Session(
            input = protocol.getInputStream(),
            output = protocol.getOutputStream(),
            keyingMaterial = keyMaterial,
            protocol = protocol,
            socket = socket,
        )
    }

    /** BouncyCastle TLS 1.3 客户端，携带 ADB 客户端证书（RSA-PSS 签名）。 */
    private class AdbTlsClient(
        private val adbCert: X509Certificate,
        private val rsaKey: RSAKeyParameters,
    ) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {

        // 协商结果：adbd 强制 TLS 1.3（= 3.4）
        var negotiatedVersion: org.bouncycastle.tls.ProtocolVersion? = null
            private set
        var handshakeHappenedThisConnection: Boolean = false
            private set

        override fun notifyServerVersion(serverVersion: org.bouncycastle.tls.ProtocolVersion) {
            super.notifyServerVersion(serverVersion)
            negotiatedVersion = serverVersion
            handshakeHappenedThisConnection = true
        }

        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                // adb 配对使用自签名 / 系统证书，直接信任
            }

            override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials {
                // TLS 1.3 客户端认证：RSA 证书 + RSA-PSS-SHA256 签名
                val tlsCert = crypto.createCertificate(adbCert.encoded)
                val chain = Certificate(arrayOf(tlsCert))
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context),
                    crypto as BcTlsCrypto,
                    rsaKey,
                    chain,
                    SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
                )
            }
        }

        /** 导出 RFC 5705 keying material（TLS 1.3 exporter 主密钥派生）。 */
        fun exportKeyingMaterial(label: String, length: Int): ByteArray? {
            return try {
                context.exportKeyingMaterial(label, null, length)
            } catch (e: Exception) {
                null
            }
        }
    }
}