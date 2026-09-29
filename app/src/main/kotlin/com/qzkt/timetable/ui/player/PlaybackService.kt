package com.qzkt.timetable.ui.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务。
 *
 * 播放器放在 Service 里而不是页面里，这样切后台、锁屏、甚至页面被回收都还能继续放；
 * 通知栏和蓝牙耳机的控制由 Media3 的会话机制接管（默认就带通知栏控件）。
 *
 * 页面侧通过 [androidx.media3.session.MediaController] 连上来控制它。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val renderers = DefaultRenderersFactory(this)
            // 硬件解码失败时回退软件解码（真机上遇到过硬解 BAD_VALUE 配不起来）
            .setEnableDecoderFallback(true)

        // 网络流走这里取数据：请求头按播放时填的内容注入（防盗链），
        // 否则不少站直接返回 403 或连上就断。
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSource = ResolvingDataSource.Factory(http, StreamHeaders::applyTo)

        val player = ExoPlayer.Builder(this, renderers, DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                // 被电话/别的应用打断时自动暂停或压低音量，音频焦点交给系统管
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
            }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // 从最近任务里划掉应用后，如果没在播就顺手停掉服务，别留着占内存
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
