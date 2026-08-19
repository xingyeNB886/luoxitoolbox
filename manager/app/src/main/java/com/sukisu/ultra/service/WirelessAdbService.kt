package com.sukisu.ultra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.sukisu.ultra.R
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
 * 类似 Shizuku 的通知栏启动方式：
 *   1. 显示常驻通知，带"打开无线调试"按钮
 *   2. 通知栏 RemoteInput 直接输入配对码 + IP:Port
 *   3. 后台完成 ADB 配对，通知更新状态
 */
class WirelessAdbService : Service() {

    companion object {
        private const val TAG = "WirelessAdbService"
        private const val CHANNEL_ID = "wireless_adb_channel"
        private const val NOTIF_ID = 10086

        // 通知栏 Action
        const val ACTION_START = "com.sukisu.ultra.action.START_WIRELESS_ADB"
        const val ACTION_STOP = "com.sukisu.ultra.action.STOP_WIRELESS_ADB"
        const val ACTION_OPEN_SETTINGS = "com.sukisu.ultra.action.OPEN_WIRELESS_SETTINGS"
        const val ACTION_INPUT_PAIR = "com.sukisu.ultra.action.INPUT_PAIR_CODE"

        // RemoteInput Key
        const val KEY_PAIR_INPUT = "pair_input"

        // 通知栏点击 → 打开应用
        const val ACTION_OPEN_APP = "com.sukisu.ultra.action.OPEN_APP"

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                showNotificationWaiting()
            }
            ACTION_STOP -> {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_SETTINGS -> {
                openWirelessDebugSettings()
                // 保持通知
                showNotificationWaiting()
            }
            ACTION_INPUT_PAIR -> {
                val input = RemoteInput.getResultsFromIntent(intent)
                if (input != null) {
                    val pairInput = input.getCharSequence(KEY_PAIR_INPUT)?.toString()?.trim() ?: ""
                    handlePairInput(pairInput)
                }
            }
            ACTION_OPEN_APP -> {
                openApp()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wireless_adb_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.wireless_adb_notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 等待配对状态通知
     * - 点击 → 打开无线调试设置
     * - RemoteInput → 输入配对码 + IP:Port
     */
    private fun showNotificationWaiting() {
        val openSettingsIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_OPEN_SETTINGS
        }
        val openSettingsPI = PendingIntent.getService(
            this, 1, openSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // RemoteInput: 通知栏直接输入
        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel(getString(R.string.wireless_adb_notif_input_label))
            .build()

        val pairIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_INPUT_PAIR
        }
        val pairPI = PendingIntent.getService(
            this, 2, pairIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pairAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.wireless_adb_notif_pair_action),
            pairPI
        ).addRemoteInput(remoteInput).build()

        val openSettingsAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_preferences,
            getString(R.string.wireless_adb_notif_open_settings),
            openSettingsPI
        ).build()

        // 点击通知 → 打开应用
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPI = if (openAppIntent != null) {
            PendingIntent.getActivity(
                this, 3, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_waiting))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPI)
            .addAction(openSettingsAction)
            .addAction(pairAction)
            .addAction(buildStopAction())
            .build()

        startForeground(NOTIF_ID, notification)
    }

    /**
     * 配对中状态
     */
    private fun showNotificationPairing() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_pairing))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .addAction(buildStopAction())
            .build()

        startForeground(NOTIF_ID, notification)
    }

    /**
     * 配对成功状态
     */
    private fun showNotificationSuccess() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth_connected)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_paired))
            .setOngoing(true)
            .setSilent(true)
            .addAction(buildStopAction())
            .build()

        startForeground(NOTIF_ID, notification)
    }

    /**
     * 配对失败状态（保持等待，允许重新输入）
     */
    private fun showNotificationFailed(errorMsg: String) {
        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel(getString(R.string.wireless_adb_notif_input_label))
            .build()

        val pairIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_INPUT_PAIR
        }
        val pairPI = PendingIntent.getService(
            this, 2, pairIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pairAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.wireless_adb_notif_retry),
            pairPI
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.wireless_adb_notif_title))
            .setContentText(getString(R.string.wireless_adb_notif_failed, errorMsg))
            .setOngoing(true)
            .setSilent(true)
            .addAction(pairAction)
            .addAction(buildStopAction())
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun buildStopAction(): NotificationCompat.Action {
        val stopIntent = Intent(this, WirelessAdbService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPI = PendingIntent.getService(
            this, 4, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.wireless_adb_notif_stop),
            stopPI
        ).build()
    }

    // ---- 配对处理 ----

    /**
     * 解析通知栏输入并执行配对
     * 格式: "配对码 IP:端口" 如 "123456 192.168.1.5:43211"
     */
    private fun handlePairInput(input: String) {
        val parts = input.split("\\s+".toRegex())
        if (parts.size < 2) {
            showNotificationFailed(getString(R.string.wireless_adb_notif_format_error))
            return
        }

        val code = parts[0].trim()
        val hostPort = parts[1].trim()

        if (!code.matches(Regex("\\d{6}"))) {
            showNotificationFailed(getString(R.string.wireless_adb_notif_format_error))
            return
        }

        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx == hostPort.length - 1) {
            showNotificationFailed(getString(R.string.wireless_adb_notif_format_error))
            return
        }

        val host = hostPort.substring(0, colonIdx).trim()
        val port = hostPort.substring(colonIdx + 1).trim().toIntOrNull()
        if (host.isEmpty() || port == null || port <= 0 || port > 65535) {
            showNotificationFailed(getString(R.string.wireless_adb_notif_format_error))
            return
        }

        // 开始配对
        showNotificationPairing()

        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                WirelessAdbManager.pair(host, port, code)
            }
            when (result) {
                is WirelessAdbManager.PairResult.Success -> {
                    Log.i(TAG, "通知栏配对成功")
                    showNotificationSuccess()
                }
                is WirelessAdbManager.PairResult.Failure -> {
                    Log.e(TAG, "通知栏配对失败: ${result.message}")
                    showNotificationFailed(result.message)
                }
            }
        }
    }

    // ---- 工具 ----

    private fun openWirelessDebugSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                Log.e(TAG, "无法打开无线调试设置")
            }
        }
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }
}
