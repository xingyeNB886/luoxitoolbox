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
 * 安全格机执行期间把媒体音量拉到最大并持续压满：
 * start() 立即开始，外部任何方式调低都会在 1ms 内被升回；
 * 只有 stop() 能停下（停止后不再自动升高）。
 */
object VolumeBooster {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            val am = ksuApp.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@launch
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            while (isActive) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
                delay(1L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
