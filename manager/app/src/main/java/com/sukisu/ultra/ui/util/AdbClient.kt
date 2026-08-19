package com.sukisu.ultra.ui.util

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * 洛茜工具箱 · ADB 协议客户端
 *
 * 连接 adbd（无线调试端口 5555），完成 ADB 认证（TLS 或 RSA Token），
 * 然后通过 ADB 协议执行 shell 命令。
 *
 * 流程（与 AOSP adb 客户端一致）：
 *   1. 建立 TCP 连接，发送 CNXN 包
 *   2. 如果收到 STLS → 升级到 TLS 1.3（用 ADB 证书做客户端认证）
 *   3. 如果收到 AUTH(TOKEN) → 用 RSA 私钥签名，回 AUTH(SIGNATURE)
 *   4. 认证通过后收到 CNXN → 可以执行命令
 *   5. 发送 OPEN("shell:xxx") 执行命令，通过 WRTE 读取输出
 *
 * 参考：moe.shizuku.manager.adb.AdbClient
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey,
    private val certificate: X509Certificate,
    private val deviceName: String = "LuoxiToolbox",
) : Closeable {

    companion object {
        private const val TAG = "AdbClient"

        // ADB 协议命令常量（与 AOSP 一致）
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_STLS = 0x534C5453
        private const val A_VERSION = 0x01000000
        private const val A_MAXDATA = 4096
        private const val A_STLS_VERSION = 0x01000000

        // ADB AUTH 类型
        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    /**
     * 连接 adbd 并完成认证。
     * @throws Exception 连接失败、认证失败等
     */
    fun connect() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        // 1. 发送 CNXN
        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()

        // 2. 检查是否需要 TLS 升级
        if (message.command == A_STLS) {
            Log.d(TAG, "服务端要求 TLS 升级")
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = createSslContext()
            tlsSocket = sslContext.socketFactory.createSocket(
                socket, host, port, true
            ) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "TLS 握手成功")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        }

        // 3. 处理 ADB 认证（TOKEN → SIGNATURE → RSAPUBLICKEY）
        if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) {
                throw IllegalStateException("期望 ADB_AUTH_TOKEN，实际 arg0=${message.arg0}")
            }
            Log.d(TAG, "收到 ADB TOKEN，签名中...")

            // 用 RSA 私钥签名 TOKEN
            val signature = adbSign(message.data ?: byteArrayOf())
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)

            message = read()

            // 如果还不是 CNXN，说明签名没通过，发送公钥让设备确认
            if (message.command != A_CNXN) {
                Log.d(TAG, "签名未被识别，发送 RSA 公钥")
                val adbPublicKey = encodeAdbPublicKey(publicKey, deviceName)
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) {
            throw IllegalStateException("ADB 认证失败，最终消息 command=${message.command}")
        }
        Log.i(TAG, "ADB 连接 & 认证成功")
    }

    /**
     * 执行 shell 命令，通过回调返回输出（每块数据调用一次）。
     */
    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0 && message.data != null) {
                            listener?.invoke(message.data)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        throw IllegalStateException("期望 WRTE 或 CLSE，实际 command=${message.command}")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                throw IllegalStateException("期望 OKAY 或 CLSE，实际 command=${message.command}")
            }
        }
    }

    // ---- ADB 协议底层 ----

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "发送: ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val header = ByteArray(24)
        inputStream.readFully(header)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        val checksum = buf.int
        val magic = buf.int

        val data: ByteArray? = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it) }
        } else null

        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "接收: ${message.toStringShort()}")
        return message
    }

    // ---- TLS ----

    private fun createSslContext(): SSLContext {
        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "adb_key"

            override fun chooseClientAlias(
                keyTypes: Array<out String>,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?
            ): String? {
                for (kt in keyTypes) {
                    if (kt.equals("RSA", ignoreCase = true)) return alias
                }
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                return if (alias == this.alias) arrayOf(certificate) else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return if (alias == this.alias) privateKey else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) =
                null

            override fun getServerAliases(
                keyType: String,
                issuers: Array<out java.security.Principal>?
            ): Array<String>? = null
        }

        val trustManager = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), java.security.SecureRandom())
        return sslContext
    }

    // ---- RSA 签名 ----

    /**
     * ADB TOKEN 签名：PKCS#1 v1.5 + SHA1（与 AOSP adb 一致）
     * 实际是：对 token 做"RSA with padding"的签名，padding 用 PKCS#1 v1.5 风格
     */
    private fun adbSign(token: ByteArray): ByteArray {
        // ADB 用的是老式 RSA 签名：先做 SHA-1 hash，再 PKCS#1 v1.5 padding，再私钥加密
        // 我们用 Cipher "RSA/ECB/PKCS1Padding" 手动做 padding
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        // ADB 签名的 padding 是 DigestInfo (SHA-1) + token
        // 实际上 adbd 会调用 RSA_verify 验证 signature == token（在 RSA padding 解包后）
        // 更准确的做法：直接对 token 做"RSA decrypt with private key"，不做 hash
        // 但 Java Cipher 只能做加密/解密，要做签名用 Signature。
        // 最简单正确的做法：用 "NONEwithRSA" + 手动加 PKCS#1 v1.5 padding
        return try {
            val sig = java.security.Signature.getInstance("NONEwithRSA")
            sig.initSign(privateKey)
            // ADB 实际上做的是：signature = RSA_private_encrypt(token_len, token, sig, key, RSA_PKCS1_PADDING)
            // 即直接对 token 做 PKCS#1 padding + 私钥加密，不做 hash
            // NONEwithRSA 需要数据长度等于 modulus 字节数 - 11（PKCS1 padding）
            // 但 token 通常是 20 字节（SHA-1），所以我们直接传
            sig.update(token)
            sig.sign()
        } catch (e: Exception) {
            // 回退：用 Cipher 做 RSA/ECB/PKCS1Padding
            cipher.doFinal(token)
        }
    }

    // ---- ADB 公钥编码 ----

    private fun encodeAdbPublicKey(publicKey: RSAPublicKey, name: String): ByteArray {
        // 复用 WirelessAdbManager 里的 AndroidPubkey 编码逻辑
        val androidKey = AndroidPubkeyAdb.encode(publicKey)
        val suffix = " $name\u0000"
        return androidKey.toByteArray(Charsets.US_ASCII) + suffix.toByteArray(Charsets.US_ASCII)
    }

    override fun close() {
        try { plainInputStream.close() } catch (_: Exception) {}
        try { plainOutputStream.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
        if (useTls) {
            try { tlsInputStream.close() } catch (_: Exception) {}
            try { tlsOutputStream.close() } catch (_: Exception) {}
            try { tlsSocket.close() } catch (_: Exception) {}
        }
    }

    // ---- ADB 消息 ----

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data_length: Int,
        val checksum: Int,
        val magic: Int,
        val data: ByteArray?,
    ) {
        constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) :
                this(
                    command, arg0, arg1,
                    data?.size ?: 0,
                    computeChecksum(data),
                    command xor -0x1,
                    data
                )

        constructor(command: Int, arg0: Int, arg1: Int, data: String) :
                this(command, arg0, arg1, data.toByteArray(Charsets.UTF_8))

        fun toByteArray(): ByteArray {
            val buf = ByteBuffer.allocate(24 + (data?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(data_length)
            buf.putInt(checksum)
            buf.putInt(magic)
            if (data != null && data.isNotEmpty()) {
                buf.put(data)
            }
            return buf.array()
        }

        fun validateOrThrow() {
            val expectedMagic = command xor -0x1
            if (magic != expectedMagic) {
                throw IllegalStateException("ADB 消息 magic 不匹配: expected=${expectedMagic.toString(16)} actual=${magic.toString(16)}")
            }
            // checksum 可选验证
        }

        fun toStringShort(): String {
            val cmdStr = when (command) {
                A_CNXN -> "CNXN"
                A_AUTH -> "AUTH"
                A_OPEN -> "OPEN"
                A_OKAY -> "OKAY"
                A_CLSE -> "CLSE"
                A_WRTE -> "WRTE"
                A_STLS -> "STLS"
                else -> command.toString(16)
            }
            return "$cmdStr arg0=$arg0 arg1=$arg1 len=$data_length"
        }

        companion object {
            private fun computeChecksum(data: ByteArray?): Int {
                if (data == null || data.isEmpty()) return 0
                var sum = 0
                for (b in data) {
                    sum += b.toInt() and 0xff
                }
                return sum
            }
        }
    }
}

/**
 * ADB AndroidPubkey 编码（与 WirelessAdbManager 里的一致，独立一份方便 AdbClient 使用）
 */
private object AndroidPubkeyAdb {
    private const val MODULUS_SIZE = 2048 / 8
    private const val ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE
    private const val MODULUS_SIZE_WORDS = MODULUS_SIZE / 4

    fun encode(publicKey: RSAPublicKey): String {
        val keyStruct = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        keyStruct.putInt(MODULUS_SIZE_WORDS)

        val r32 = java.math.BigInteger.ZERO.setBit(32)
        var n0inv = publicKey.modulus.mod(r32)
        n0inv = n0inv.modInverse(r32)
        n0inv = r32.subtract(n0inv)
        keyStruct.putInt(n0inv.toInt())

        keyStruct.put(bigEndianToLittleEndianPadded(MODULUS_SIZE, publicKey.modulus))

        var rr = java.math.BigInteger.ZERO.setBit(MODULUS_SIZE * 8)
        rr = rr.modPow(java.math.BigInteger.valueOf(2), publicKey.modulus)
        keyStruct.put(bigEndianToLittleEndianPadded(MODULUS_SIZE, rr))

        keyStruct.putInt(publicKey.publicExponent.toInt())

        return android.util.Base64.encodeToString(keyStruct.array(), android.util.Base64.NO_WRAP)
    }

    private fun bigEndianToLittleEndianPadded(len: Int, value: java.math.BigInteger): ByteArray {
        val out = ByteArray(len)
        val bytes = value.toByteArray()
        val real = ByteArray(bytes.size)
        for (i in bytes.indices) real[i] = bytes[bytes.size - i - 1]
        var offset = 0
        while (offset < real.size && real[offset] == 0.toByte()) offset++
        val usable = real.size - offset
        System.arraycopy(real, offset, out, 0, minOf(usable, len))
        return out
    }
}