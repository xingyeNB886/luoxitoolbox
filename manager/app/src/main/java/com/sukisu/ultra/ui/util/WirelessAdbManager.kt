package com.sukisu.ultra.ui.util

import android.util.Base64
import android.util.Log
import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 洛茜工具箱 · 无线调试配对管理器
 *
 * 通过无线调试获取 ADB 级权限，无需安装 Shizuku。
 *
 * 本实现移植自开源 Kadb / Shizuku 的 ADB 配对协议（同 AOSP 兼容）：
 *   1. TLS 1.3 连接（客户端证书即 ADB RSA 密钥），由 BouncyCastle(bctls) 完成并导出密钥
 *   2. 从 TLS 会话导出 keying material（label = "adb-label\0"，64 字节）
 *   3. SPAKE25519（Edwards25519 上的 SPAKE2），密码 = 配对码 + keying material
 *   4. HKDF-SHA256 派生 AES-128-GCM 密钥（info = "adb pairing_auth aes-128-gcm key"）
 *   5. AES-128-GCM 加密交换 PeerInfo（递增 sequence 作为 nonce）
 *
 * 之前的实现因简化了 SPAKE2（X25519 标量乘法）而无法通过服务端校验，
 * 并且用安卓隐藏 API(Conscrypt) 反射取连接密钥，在部分 ROM/安卓版本上不可用。
 * 这里 SPAKE2 改用与 BoringSSL 测试向量对齐的纯 Java 实现，
 * TLS 与密钥导出改用 BouncyCastle，不再依赖任何系统隐藏接口。
 */
object WirelessAdbManager {

    private const val TAG = "WirelessAdb"

    // ---- 配对协议常量（与 AOSP pairing_connection 一致） ----
    private const val HEADER_VERSION: Byte = 1
    private const val TYPE_SPAKE2_MSG: Byte = 0
    private const val TYPE_PEER_INFO: Byte = 1
    private const val HEADER_SIZE = 6 // version(1) + type(1) + payload(4)

    // ADB RSA 公钥类型 PeerInfo
    private const val ADB_RSA_PUB_KEY: Byte = 0
    private const val MAX_PEER_INFO_SIZE = 1 shl 13 // 8192
    private const val MAX_PEER_INFO_DATA_SIZE = MAX_PEER_INFO_SIZE - 1 // 8191

    // ---- AES-128-GCM 参数 ----
    private const val AES_KEY_LENGTH = 16
    private const val GCM_IV_LENGTH = 12

    private val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
    private val INFO: ByteArray = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

    /** 设备名称（写到 PeerInfo，会被系统展示） */
    private const val ADB_KEY_NAME = "LuoxiToolbox"

    // 证书有效期（AOSP 为 25 年）
    private const val CERT_VALIDITY_DAYS = 25L * 365

    // ---- RSA 密钥存储 ----
    private const val PREF_NAME = "wireless_adb"
    private const val KEY_PRIVATE_PEM = "rsa_private_key"
    private const val KEY_IS_PAIRED = "is_paired"

    /** 配对结果 */
    sealed class PairResult {
        object Success : PairResult()
        data class Failure(val message: String) : PairResult()
    }

    /**
     * 执行 ADB 无线配对（阻塞，需在 IO 线程调用）。
     *
     * @param host 配对服务地址（127.0.0.1）
     * @param port 配对服务端口（自动发现）
     * @param pairingCode 6 位配对码
     */
    fun pair(host: String, port: Int, pairingCode: String): PairResult {
        val code = pairingCode.trim()
        if (!code.matches(Regex("\\d{6}"))) {
            return PairResult.Failure("配对码必须是 6 位数字")
        }

        // 1. 生成 / 复用 ADB RSA 密钥
        val id = getOrCreateAdbIdentity() ?: return PairResult.Failure("无法生成 ADB 密钥")

        return try {
            // 2. BouncyCastle 完成 TLS 1.3 握手并导出 keying material（不依赖系统隐藏 API）
            val socket = java.net.Socket(host, port)
            socket.tcpNoDelay = true
            val tls = WirelessAdbTls.connect(socket, id.certificate, id.privateKey)
            val keyMaterial = tls.keyingMaterial
            if (keyMaterial.size < WirelessAdbTls.EXPORT_KEY_SIZE) {
                return PairResult.Failure("无法导出 TLS 密钥材料，请确认设备支持无线调试")
            }
            Log.d(TAG, "导出 keying material: ${keyMaterial.size} 字节")

            // 3. 密码 = 6 位配对码 + keying material
            val password = ByteArray(code.length + keyMaterial.size)
            code.toByteArray().copyInto(password, 0)
            keyMaterial.copyInto(password, code.length)

            val pairingAuth = PairingAuth(
                Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME),
                password
            )

            val conn = PairingConnection(
                tls.input, tls.output, pairingAuth, id, ADB_KEY_NAME, tls
            )
            conn.start()

            // 5. 配对成功后持久化为已配对
            markPaired()
            Log.i(TAG, "配对成功！")
            PairResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "配对异常", e)
            PairResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun isPaired(): Boolean {
        return prefs().getBoolean(KEY_IS_PAIRED, false)
    }

    private fun markPaired() {
        prefs().edit().putBoolean(KEY_IS_PAIRED, true).apply()
    }

    private fun prefs() =
        com.sukisu.ultra.ksuApp.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)

    // ==================== PairingAuth ====================

    /** SPAKE2 会话 + AES-128-GCM，持有每次配对的状态 */
    private class PairingAuth(
        private val mSpake2Ctx: Spake2Context,
        password: ByteArray,
    ) {
        /** 我们要发送的 SPAKE2 消息 */
        val msg: ByteArray = mSpake2Ctx.generateMessage(password)

        private val mSecretKey = ByteArray(AES_KEY_LENGTH)
        private var mDecIv = 0L
        private var mEncIv = 0L
        private var mInitialized = false
        private var mDestroyed = false

        /** 用对端 SPAKE2 消息初始化 AES 密钥；返回是否成功 */
        fun initCipher(theirMsg: ByteArray?): Boolean {
            if (mDestroyed || mInitialized) return false
            val keyMaterial = mSpake2Ctx.processMessage(theirMsg) ?: return false
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, INFO))
            hkdf.generateBytes(mSecretKey, 0, mSecretKey.size)
            mInitialized = true
            return true
        }

        fun encrypt(input: ByteArray): ByteArray? {
            val iv = ByteBuffer.allocate(GCM_IV_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(mEncIv++).array()
            return encryptDecrypt(true, input, iv)
        }

        fun decrypt(input: ByteArray): ByteArray? {
            val iv = ByteBuffer.allocate(GCM_IV_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(mDecIv++).array()
            return encryptDecrypt(false, input, iv)
        }

        private fun encryptDecrypt(forEncryption: Boolean, input: ByteArray, iv: ByteArray): ByteArray? {
            if (mDestroyed) return null
            val spec = AEADParameters(KeyParameter(mSecretKey), mSecretKey.size * 8, iv)
            val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(forEncryption, spec)
            val out = ByteArray(cipher.getOutputSize(input.size))
            val newOffset = cipher.processBytes(input, 0, input.size, out, 0)
            try {
                cipher.doFinal(out, newOffset)
            } catch (e: Exception) {
                return null
            }
            return out
        }

        fun destroy() {
            mDestroyed = true
            mSecretKey.fill(0)
            mSpake2Ctx.destroy()
        }
    }

    // ==================== PairingConnection ====================

    /** 执行 SPAKE2_MSG 与 PEER_INFO 两次交换 */
    private class PairingConnection(
        private val mInputStream: java.io.InputStream,
        private val mOutputStream: java.io.OutputStream,
        private val mAuth: PairingAuth,
        mIdentity: AdbIdentity,
        deviceName: String,
        private val mTls: java.io.Closeable,
    ) {
        private val mPeerInfo: ByteArray = encodePeerInfo(mIdentity, deviceName)

        @Throws(java.io.IOException::class)
        fun start() {
            // 交换 SPAKE2 消息
            writePacket(TYPE_SPAKE2_MSG, mAuth.msg)
            val (theirType, theirLen) = readHeader()
            if (theirType != TYPE_SPAKE2_MSG || theirLen <= 0) {
                throw java.io.IOException("分别的消息类型不匹配 (SPAKE2_MSG)")
            }
            val theirSpakeMsg = readN(theirLen)
            if (!mAuth.initCipher(theirSpakeMsg)) {
                throw java.io.IOException("配对码验证失败（SPAKE2）")
            }
            Log.d(TAG, "SPAKE2 完成，AES 密钥就绪")

            // 交换加密的 PeerInfo
            val encryptedPeerInfo = mAuth.encrypt(mPeerInfo) ?: throw java.io.IOException("加密 PeerInfo 失败")
            writePacket(TYPE_PEER_INFO, encryptedPeerInfo)
            val (theirType2, theirLen2) = readHeader()
            if (theirType2 != TYPE_PEER_INFO || theirLen2 <= 0) {
                throw java.io.IOException("对端消息类型不匹配 (PEER_INFO)")
            }
            val theirEncrypted = readN(theirLen2)
            val decrypted = mAuth.decrypt(theirEncrypted) ?: throw java.io.IOException("对端证书解密失败")
            if (decrypted.size != MAX_PEER_INFO_SIZE) {
                throw java.io.IOException("对端 PeerInfo 长度不符")
            }

            closeSilently()
        }

        private fun writePacket(type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
                .put(HEADER_VERSION.toByte()).put(type).putInt(payload.size).array()
            mOutputStream.write(header)
            mOutputStream.write(payload)
            mOutputStream.flush()
        }

        private fun readHeader(): Pair<Byte, Int> {
            val header = readN(HEADER_SIZE)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get()
            val type = buf.get()
            val payload = buf.int
            if (version != HEADER_VERSION.toByte()) {
                throw java.io.IOException("不支持的协议版本: $version")
            }
            return type to payload
        }

        private fun readN(n: Int): ByteArray {
            val buf = ByteArray(n)
            var total = 0
            while (total < n) {
                val r = mInputStream.read(buf, total, n - total)
                if (r < 0) throw java.io.IOException("读取到 EOF，期望 $n 字节，只读到 $total")
                total += r
            }
            return buf
        }

        private fun closeSilently() {
            try {
                mAuth.destroy()
            } catch (_: Exception) {
            }
            try {
                mTls.close()
            } catch (_: Exception) {
            }
        }
    }

    // ==================== PeerInfo 编码 ====================

    private fun encodePeerInfo(id: AdbIdentity, deviceName: String): ByteArray {
        // AndroidPubkey 编码 RSA 公钥 → base64；加上 " 名称\0"。
        val androidKey = AndroidPubkey.encode(id.publicKey)
        val data = androidKey + " $deviceName\u0000".toByteArray(Charsets.UTF_8)
        val peerInfo = java.io.ByteArrayOutputStream(MAX_PEER_INFO_SIZE).apply {
            write(ADB_RSA_PUB_KEY.toInt())
            write(data.toByteArray())
        }
        return peerInfo.toByteArray()
    }

    // ==================== ADB 身份（RSA + 证书） ====================

    private class AdbIdentity(
        val privateKey: PrivateKey,
        val publicKey: RSAPublicKey,
        val certificate: X509Certificate,
    )

    private var cachedIdentity: AdbIdentity? = null

    private fun getOrCreateAdbIdentity(): AdbIdentity? {
        cachedIdentity?.let { return it }
        val prefs = prefs()
        val pem = prefs.getString(KEY_PRIVATE_PEM, null) ?: return generateAdbIdentity()

        return try {
            val pkcs8 = parsePrivateKeyPem(pem)
            val publicKey = deriveRsaPublicKey(pkcs8)
            val cert = generateCertificate(pkcs8, publicKey)
            AdbIdentity(pkcs8, publicKey, cert).also { cachedIdentity = it }
        } catch (e: Exception) {
            Log.w(TAG, "加载已有 ADB 密钥失败，重新生成", e)
            generateAdbIdentity()
        }
    }

    private fun generateAdbIdentity(): AdbIdentity {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        val privateKey = kp.private
        val rsaPublic = kp.public as RSAPublicKey
        val cert = generateCertificate(privateKey, rsaPublic)
        // 持久化私钥 PEM
        prefs().edit()
            .putString(KEY_PRIVATE_PEM, encodePrivateKeyPem(privateKey))
            .apply()
        return AdbIdentity(privateKey, rsaPublic, cert).also { cachedIdentity = it }
    }

    private fun generateCertificate(privateKey: PrivateKey, publicKey: RSAPublicKey): X509Certificate {
        // 证书 CN = RSA 公钥的 SHA-256 指纹（ADB 客户端证书约定）
        val fingerprint = adbSha256FingerprintHex(publicKey.encoded)
        val subject = X500Name("O=AdbKey-0, CN=$fingerprint")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.MINUTES.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(CERT_VALIDITY_DAYS))

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.ONE,
            notBefore,
            notAfter,
            subject,
            publicKey
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )
        val spki = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            BcX509ExtensionUtils().createSubjectKeyIdentifier(spki)
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun adbSha256FingerprintHex(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { b ->
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    private fun encodePrivateKeyPem(privateKey: PrivateKey): String {
        val b64 = Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
    }

    private fun parsePrivateKeyPem(pem: String): PrivateKey {
        val clean = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun deriveRsaPublicKey(privateKey: PrivateKey): RSAPublicKey {
        if (privateKey is java.security.interfaces.RSAPrivateCrtKey) {
            val spec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
        }
        throw IllegalArgumentException("密钥不是 RSA-CRT 格式，无法派生公钥")
    }

    // ==================== AndroidPubkey 编码 ====================

    private object AndroidPubkey {
        private const val MODULUS_SIZE = 2048 / 8
        private const val ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE
        private const val MODULUS_SIZE_WORDS = MODULUS_SIZE / 4

        fun encode(publicKey: RSAPublicKey): String {
            val keyStruct = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            keyStruct.putInt(MODULUS_SIZE_WORDS) // modulus_size_words

            // n0inv = 2^32 - (1/N mod 2^32) mod 2^32
            val r32 = BigInteger.ZERO.setBit(32)
            var n0inv = publicKey.modulus.mod(r32)
            n0inv = n0inv.modInverse(r32)
            n0inv = r32.subtract(n0inv)
            keyStruct.putInt(n0inv.toInt())

            // n (little-endian, padded)
            keyStruct.put(bigEndianToLittleEndianPadded(MODULUS_SIZE, publicKey.modulus))

            // rr = 2^(rsa_size*2) mod N
            var rr = BigInteger.ZERO.setBit(MODULUS_SIZE * 8)
            rr = rr.modPow(BigInteger.valueOf(2), publicKey.modulus)
            keyStruct.put(bigEndianToLittleEndianPadded(MODULUS_SIZE, rr))

            // exponent
            keyStruct.putInt(publicKey.publicExponent.toInt())

            return Base64.encodeToString(keyStruct.array(), Base64.NO_WRAP)
        }

        private fun bigEndianToLittleEndianPadded(len: Int, value: BigInteger): ByteArray {
            val out = ByteArray(len)
            val bytes = value.toByteArray()
            val real = ByteArray(bytes.size)
            for (i in bytes.indices) real[i] = bytes[bytes.size - i - 1] // big endian → little endian
            // 忽略前导符号字节
            var offset = 0
            while (offset < real.size && real[offset] == 0.toByte()) offset++
            val usable = real.size - offset
            System.arraycopy(real, offset, out, 0, minOf(usable, len))
            return out
        }
    }
}