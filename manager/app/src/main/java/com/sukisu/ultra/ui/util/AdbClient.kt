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

/**
 * 洛茜工具箱 · ADB 协议客户端
 *
 * 连接 adbd（无线调试端口），完成 ADB 认证（TLS 或 RSA Token），
 * 然后通过 ADB 协议执行 shell 命令。
 *
 * TLS 握手复用 WirelessAdbTls（BouncyCastle 实现，不依赖系统隐藏 API）。
 *
 * 流程（与 AOSP adb 客户端一致）：
 *   1. 建立 TCP 连接，发送 CNXN 包
 *   2. 如果收到 STLS → 升级到 TLS 1.3（用 ADB 证书做客户端认证）
 *   3. 如果收到 AUTH(TOKEN) → 用 RSA 私钥签名，回 AUTH(SIGNATURE)
 *   4. 认证通过后收到 CNXN → 可以执行命令
 *   5. 发送 OPEN("shell:xxx") 执行命令，通过 WRTE 读取输出
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

        // ADB 协议命令常量（与 AOSP 一致，小端序 4 字节魔数）
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
    private var tlsSession: WirelessAdbTls.Session? = null

    private val inputStream: DataInputStream
        get() = if (useTls) DataInputStream(tlsSession!!.input) else plainInputStream
    private val outputStream: DataOutputStream
        get() = if (useTls) DataOutputStream(tlsSession!!.output) else plainOutputStream

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
            // 回复 STLS，同意升级
            write(A_STLS, A_STLS_VERSION, 0)

            // 用 BouncyCastle 做 TLS 1.3 握手（复用配对用的 TLS 实现）
            tlsSession = WirelessAdbTls.connect(socket, certificate, privateKey)
            useTls = true
            Log.d(TAG, "TLS 握手成功")

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
            throw IllegalStateException("ADB 认证失败，最终消息 command=${message.command.toString(16)}")
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
                        throw IllegalStateException("期望 WRTE 或 CLSE，实际 command=${message.command.toString(16)}")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                throw IllegalStateException("期望 OKAY 或 CLSE，实际 command=${message.command.toString(16)}")
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

    // ---- RSA 签名 ----

    /**
     * ADB TOKEN 签名：用 RSA 私钥做 PKCS#1 v1.5 签名。
     * adbd 端用 RSA_verify(PKCS1_PADDING) 验证。
     *
     * 注意：ADB 的 token 签名不是标准的 "SHA1withRSA"，
     * 而是直接对 token 做 RSA_private_encrypt（PKCS#1 padding）。
     */
    private fun adbSign(token: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(token)
    }

    // ---- ADB 公钥编码 ----

    private fun encodeAdbPublicKey(publicKey: RSAPublicKey, name: String): ByteArray {
        val androidKey = AndroidPubkeyAdb.encode(publicKey)
        val suffix = " $name\u0000"
        return androidKey.toByteArray(Charsets.US_ASCII) + suffix.toByteArray(Charsets.US_ASCII)
    }

    override fun close() {
        try { tlsSession?.close() } catch (_: Exception) {}
        try { plainInputStream.close() } catch (_: Exception) {}
        try { plainOutputStream.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
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
                throw IllegalStateException(
                    "ADB 消息 magic 不匹配: expected=${expectedMagic.toString(16)} actual=${magic.toString(16)}"
                )
            }
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