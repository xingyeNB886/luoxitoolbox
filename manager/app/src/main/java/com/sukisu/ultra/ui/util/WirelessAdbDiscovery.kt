package com.sukisu.ultra.ui.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 洛茜工具箱 · 无线调试配对端口自动发现
 *
 * 和 Shizuku 一样：安卓开启"无线调试"后，设备会通过 mDNS 广播
 * `_adb-tls-pairing._tcp` 服务，里面带有当前的配对端口。
 *
 * 配对隧道其实就在本机，因此主机地址固定为 127.0.0.1，
 * 用户只需要输入 6 位配对码即可，IP 和端口全部自动完成。
 */
object WirelessAdbDiscovery {

    private const val TAG = "WirelessAdbDiscovery"

    // ADB 无线调试配对服务的 mDNS 类型
    private const val SERVICE_TYPE = "_adb-tls-pairing._tcp"

    /**
     * 通过 mDNS 自动发现当前无线调试的配对端口。
     *
     * @return 发现到的配对端口；超时或失败时返回 null
     */
    suspend fun discoverPairingPort(context: Context): Int? {
        return try {
            suspendCancellableCoroutine { cont ->
                val nsdManager: NsdManager =
                    context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                        ?: run {
                            cont.resume(null)
                            return@suspendCancellableCoroutine
                        }
                var finished = false

                // 提前声明，避免局部函数引用后定义变量的作用域问题
                var resolveListener: NsdManager.ResolveListener? = null
                var discoveryListener: NsdManager.DiscoveryListener? = null

                fun finish(port: Int?) {
                    if (finished) return
                    finished = true
                    try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
                    if (cont.isActive) {
                        cont.resume(port)
                    }
                }

                resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "解析 mDNS 服务失败: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo?) {
                        val port = info?.port ?: 0
                        Log.i(TAG, "发现配对服务: ${info?.serviceName} 端口=$port")
                        if (port > 0) finish(port)
                    }
                }

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(sType: String) {
                        Log.d(TAG, "开始搜索 $sType")
                    }

                    override fun onServiceFound(si: NsdServiceInfo) {
                        // 匹配 ADB 配对服务
                        if (si.serviceType.contains("adb-tls-pairing", ignoreCase = true)) {
                            Log.d(TAG, "找到配对服务: ${si.serviceName}")
                            resolveListener?.let { nsdManager.resolveService(si, it) }
                        }
                    }

                    override fun onServiceLost(si: NsdServiceInfo?) {}

                    override fun onDiscoveryStopped(sType: String) {}

                    override fun onStartDiscoveryFailed(sType: String, errorCode: Int) {
                        Log.e(TAG, "启动搜索失败: $errorCode")
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(sType: String, errorCode: Int) {
                        Log.e(TAG, "停止搜索失败: $errorCode")
                    }
                }

                try {
                    nsdManager.discoverServices(
                        SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener!!
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "discoverServices 异常", e)
                    finish(null)
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "自动发现配对端口失败", e)
            null
        }
    }

    /**
     * 等待一段时间执行发现（配合超时）。
     * 由于 NsdManager 需要主线程 Looper，必须在主线程协程中调用。
     */
    suspend fun discoverPairingPortWithTimeout(
        context: Context,
        timeoutMs: Long = 12_000
    ): Int? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                discoverPairingPort(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发现超时", e)
            null
        }
    }
}