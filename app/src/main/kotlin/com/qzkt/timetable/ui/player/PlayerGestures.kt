package com.qzkt.timetable.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * 播放画面的快捷手势：
 * - 横向拖动：快进 / 快退（松手生效，拖动全程有提示）
 * - 左半边上下拖动：亮度（改的是窗口亮度，只影响当前页面）
 * - 右半边上下拖动：音量
 * - 双击：播放 / 暂停
 * - 单击：显示 / 隐藏控制条（复刻 PlayerView 自带的单击行为，延迟一点给双击留识别窗口）
 *
 * 挂在 [PlayerView] 上：控制条上的按钮（播放、进度条、选集、清晰度、全屏、设置）
 * 都是它的子 View，触摸先分发给子 View，不会走到这里 —— 手势和控制条互不干扰。
 */
class PlayerGestures(
    private val view: PlayerView,
    private val activity: Activity?,
    private val player: () -> Player?,
    private val onHint: (String?) -> Unit,
) : View.OnTouchListener {

    private val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val handler = Handler(Looper.getMainLooper())

    private var mode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var startSeekMs = 0L
    private var seekTargetMs = 0L
    private var startBrightness = 0f
    private var startVolume = 0
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var pendingToggle: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        view.setOnTouchListener(this)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                mode = MODE_NONE
                val now = SystemClock.uptimeMillis()
                if (now - lastTapAt < DOUBLE_TAP_MS && abs(event.x - lastTapX) < view.width / 4f) {
                    // 双击：撤掉还没执行的单击切换，直接播放/暂停
                    pendingToggle?.let { handler.removeCallbacks(it) }
                    pendingToggle = null
                    player()?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                lastTapAt = now
                lastTapX = event.x
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (mode == MODE_NONE && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                    mode = when {
                        abs(dx) > abs(dy) -> MODE_SEEK
                        downX < view.width / 2f && activity != null -> MODE_BRIGHTNESS
                        else -> MODE_VOLUME
                    }
                    when (mode) {
                        MODE_SEEK -> startSeekMs = player()?.currentPosition ?: 0L
                        MODE_BRIGHTNESS -> startBrightness = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: 0.5f
                        MODE_VOLUME -> startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                }
                when (mode) {
                    MODE_SEEK -> {
                        val duration = player()?.duration?.takeIf { it > 0 }
                        seekTargetMs = (startSeekMs + dx / view.width * 90_000).roundToLong()
                        seekTargetMs = if (duration != null) {
                            seekTargetMs.coerceIn(0L, duration)
                        } else {
                            seekTargetMs.coerceAtLeast(0L)
                        }
                        onHint(
                            if (seekTargetMs >= startSeekMs) {
                                "快进 ${(seekTargetMs - startSeekMs) / 1000} 秒"
                            } else {
                                "快退 ${(startSeekMs - seekTargetMs) / 1000} 秒"
                            },
                        )
                    }
                    MODE_BRIGHTNESS -> {
                        val value = (startBrightness - dy / view.height).coerceIn(0.02f, 1f)
                        activity?.window?.let { window ->
                            val attributes = window.attributes
                            attributes.screenBrightness = value
                            window.attributes = attributes
                        }
                        onHint("亮度 ${(value * 100).toInt()}%")
                    }
                    MODE_VOLUME -> {
                        val volume = (startVolume - dy / view.height * maxVolume).roundToInt()
                            .coerceIn(0, maxVolume)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                        onHint("音量 ${volume * 100 / maxVolume}%")
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_SEEK) {
                    player()?.seekTo(seekTargetMs)
                }
                if (mode == MODE_NONE && event.actionMasked == MotionEvent.ACTION_UP) {
                    // 单击：延迟一点再切控制条，给双击留出识别窗口
                    val toggle = Runnable {
                        if (view.isControllerFullyVisible) view.hideController() else view.showController()
                    }
                    pendingToggle = toggle
                    handler.postDelayed(toggle, DOUBLE_TAP_MS)
                }
                onHint(null)
                mode = MODE_NONE
            }
        }
        return true
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_SEEK = 1
        private const val MODE_BRIGHTNESS = 2
        private const val MODE_VOLUME = 3
        private const val DOUBLE_TAP_MS = 260L
        private const val TOUCH_SLOP = 24f
    }
}
