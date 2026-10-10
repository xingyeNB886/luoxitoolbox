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
 * 安全格机执行期间把媒体音量拉到最大并持续拉满：
 * start() 立即生效，毫秒级反复拉高，用户手动调低会被立刻升回；
 * stop() 立即停止（不再自动升高）。
 */
object VolumeBooster {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            val am = ksuApp.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@launch
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            while (isActive) {
                runCatching {
                    if (am.getStreamVolume(stream) != max) {
                        am.setStreamVolume(stream, max, 0)
                    }
                }
                delay(1L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
