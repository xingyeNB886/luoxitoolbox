package com.sukisu.ultra.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.WirelessAdbDiscovery
import com.sukisu.ultra.ui.util.WirelessAdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 洛茜工具箱 · 无线调试前台服务
 *
 * 通知栏启动流程完全对齐 Shizuku 官方 AdbPairingService：
 *   1. startForegroundService + PendingIntent.getForegroundService 启动服务
 *   2. startForeground 带 FOREGROUND_SERVICE_TYPE_MANIFEST
 *   3. 捕获 ForegroundServiceStartNotAllowedException，降级用 NotificationManager.notify
 *   4. 渠道 IMPORTANCE_HIGH，确保通知一定显示
 *   5. mDNS 搜索到配对端口后，再显示"输入配对码"按钮
 */
class WirelessAdbService : Service() {

    companion object {
        private const val TAG = "WirelessAdbService"
        private const val CHANNEL_ID = "wireless_adb_channel"
        private const val NOTIF_ID = 10086

        // 通知 Action
        private const val ACTION_START = "com.sukisu.ultra.action.START_WIRELESS_ADB"
        private const val ACTION_STOP = "com.sukisu.ultra.action.STOP_WIRELESS_ADB"
        private const val ACTION_REPLY = "com.sukisu.ultra.action.REPLY_PAIR_CODE"
        private const val ACTION_OPEN_SETTINGS = "com.sukisu.ultra.action.OPEN_WIRELESS_SETTINGS"

        // RemoteInput Key
        private const val KEY_PAIR_INPUT = "pair_input"
        private const val KEY_PAIR_PORT = "pair_port"

        // request codes
        private const val REQ_START = 1
        private const val REQ_STOP = 2
        private const val REQ_REPLY = 3
        private const val REQ_OPEN_SETTINGS = 4

        fun start(context: Context) {
            val intent = Intent(context, WirelessAdbService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WirelessAdbService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // mDNS 搜索状态
    private var discoveryStarted = false
    private var currentPort: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> {
                onStart()
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_PAIR_INPUT)?.toString() ?: ""
                val port = intent.getIntExtra(KEY_PAIR_PORT, -1)
                if (port != -1) {
                    onInput(code, port)
                } else {
                    onStart()
                }
            }
            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            ACTION_OPEN_SETTINGS -> {
                openWirelessDebugSettings()
                searchingNotification()
            }
            else -> {
                // 兜底：任何入口都先显示搜索通知
                onStart()
            }
        }

        if (notification != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                    )
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startForeground failed", e)
                // 降级：用 NotificationManager.notify 显示通知（部分 ROM 上 startForeground 被限制时）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException
                ) {
                    try {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, notification)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        serviceScope.cancel()
    }

    // ---- 通知渠道 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wireless_adb_notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.wireless_adb_notif_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ---- mDNS 搜索 ----

    private fun startSearch() {
        if (discoveryStarted) return
        discoveryStarted = true

        serviceScope.launch {
            val port = withContext(Dispatchers.Main) {
                WirelessAdbDiscovery.discoverPairingPortWithTimeout(this@WirelessAdbService, 30_000)
            }
            if (port != null && port > 0) {
                currentPort = port
                Log.i(TAG, "发现配对端口: $port")
                // 找到端口后，更新通知为"输入配对码"状态
                val notif = createInputNotification(port)
                updateNotification(notif)
            }
        }
    }

    private fun stopSearch() {
        discoveryStarted = false
    }

    private fun updateNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Throwable) {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, notification)
            } catch (_: Exception) {
            }
        }
    }

    // ---- 动作处理 ----

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification()
    }

    private fun onInput(code: String, port: Int): Notification {
        val trimmedCode = code.trim()

        if (!trimmedCode.matches(Regex("\\d{6}"))) {
            return failedNotification("请输入 6 位数字配对码")
        }

        // 在 IO 线程执行配对
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                WirelessAdbManager.pair("127.0.0.1", port, trimmedCode)
            }
            when (result) {
                is WirelessAdbManager.PairResult.Success -> {
                    Log.i(TAG, "配对成功")
                    handlePairSuccess()
                }
                is WirelessAdbManager.PairResult.Failure -> {
                    Log.e(TAG, "配对失败: ${result.message}")
                    updateNotification(failedNotification(result.message))
                }
            }
        }

        return pairingNotification()
    }

    private fun handlePairSuccess() {
        stopSearch()
        updateNotification(successNotification())
        // 2 秒后停止服务
        serviceScope.launch {
            kotlinx.coroutines.delay(3000)
            stopSelf()
        }
    }

    // ---- 通知构建 ----

    private fun searchingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_adb)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_searching))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppPendingIntent())
            .addAction(openSettingsAction())
            .addAction(stopAction())
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel(getString(R.string.wireless_adb_notif_input_label))
            .build()

        val replyIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_REPLY
            putExtra(KEY_PAIR_PORT, port)
        }
        val replyPI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, REQ_REPLY, replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, REQ_REPLY, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_adb,
            getString(R.string.wireless_adb_notif_pair_action),
            replyPI
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_adb)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_waiting))
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(replyAction)
            .addAction(stopAction())
            .build()
    }

    private fun pairingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_adb)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_pairing))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(stopAction())
            .build()
    }

    private fun successNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_adb)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_paired))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun failedNotification(errorMsg: String): Notification {
        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel(getString(R.string.wireless_adb_notif_input_label))
            .build()

        val replyIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_REPLY
            putExtra(KEY_PAIR_PORT, currentPort)
        }
        val replyPI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, REQ_REPLY, replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, REQ_REPLY, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val retryAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_adb,
            getString(R.string.wireless_adb_notif_retry),
            replyPI
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_adb)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_failed, errorMsg))
            .setOngoing(false)
            .addAction(retryAction)
            .addAction(stopAction())
            .build()
    }

    private fun stopAction(): NotificationCompat.Action {
        val stopIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPI = PendingIntent.getService(
            this, REQ_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_adb,
            getString(R.string.wireless_adb_notif_stop),
            stopPI
        ).build()
    }

    private fun openSettingsAction(): NotificationCompat.Action {
        val intent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_OPEN_SETTINGS
        }
        val pi = PendingIntent.getService(
            this, REQ_OPEN_SETTINGS, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification_adb,
            getString(R.string.wireless_adb_notif_open_settings),
            pi
        ).build()
    }

    private fun openAppPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- 工具 ----

    @SuppressLint("InlinedApi")
    private fun openWirelessDebugSettings() {
        try {
            val intent = android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS.let {
                Intent(it).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                Log.e(TAG, "无法打开无线调试设置")
            }
        }
    }
}