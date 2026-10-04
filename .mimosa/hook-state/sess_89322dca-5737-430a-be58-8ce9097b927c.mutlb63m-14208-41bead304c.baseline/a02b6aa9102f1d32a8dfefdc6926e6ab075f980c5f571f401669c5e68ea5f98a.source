package com.qzkt.timetable.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

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

        // 取流走 OkHttp：连接池复用、断线自动重连、跨协议重定向原生支持。
        // 默认的 HttpURLConnection 遇到忽快忽慢的免费 CDN，一断就整个报错，
        // 表现就是频繁卡顿。
        val http = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build(),
        )

        // 请求头（防盗链）要在真正发请求的那一层注入，所以缓存层包在外面
        val upstream = ResolvingDataSource.Factory(http, StreamHeaders::applyTo)

        // 分片落盘：下过的段直接读缓存，回拖、重播、二次缓冲都不再走网络
        val dataSource = CacheDataSource.Factory()
            .setCache(playbackCache(this))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 缓冲策略：最多垫 120 秒，够 3 秒就开播；真卡住后攒够 8 秒再继续，
        // 避免「放一秒卡一下」。60 秒内回退的位置不用重新下载。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 8_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60_000, true)
            .build()

        val player = ExoPlayer.Builder(this, renderers, DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
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

    companion object {
        @Volatile
        private var cache: SimpleCache? = null

        /**
         * 进程内唯一的播放缓存。SimpleCache 对同一个目录只允许一个实例，
         * 所以放在 companion 里全局共享。上限 256MB，超出按最久未用淘汰。
         */
        fun playbackCache(context: Context): SimpleCache =
            cache ?: synchronized(this) {
                cache ?: SimpleCache(
                    File(context.cacheDir, "media_cache"),
                    LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                    StandaloneDatabaseProvider(context),
                ).also { cache = it }
            }
    }
}
