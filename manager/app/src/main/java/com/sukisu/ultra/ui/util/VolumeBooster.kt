package com.sukisu.ultra.ui.util

import android.content.Context
import android.media.AudioManager
import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 安全格机执行期间把系统各音量拉到最大并持续拉满：
 * start() 后延迟 0.5 秒开始，音量被调低会自动升回；
 * stop() 立即停止（不再自动升高）。
 */
object VolumeBooster {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_DTMF,
        AudioManager.STREAM_VOICE_CALL
    )

    fun start(delayMillis: Long = 500L) {
        stop()
        job = scope.launch {
            delay(delayMillis)
            val am = ksuApp.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@launch
            while (isActive) {
                for (stream in streams) {
                    runCatching {
                        val max = am.getStreamMaxVolume(stream)
                        if (am.getStreamVolume(stream) != max) {
                            am.setStreamVolume(stream, max, 0)
                        }
                    }
                }
                delay(10L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
