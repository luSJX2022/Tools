package com.qzkt.timetable.ui.player.link

/**
 * 播放器那一栏的入口：给一段用户粘贴的内容，判断是不是 B站 / 抖音分享链接，
 * 是就解析成一条能直接播的地址。
 *
 * 不是分享链接（比如 `.m3u8` 直链）时 [detectShareLink] 返回 null，
 * 调用方按原来的方式当普通流地址处理。
 */
import com.qzkt.timetable.data.PluginLog

class MediaLinkResolver(
    private val bilibili: BilibiliResolver = BilibiliResolver(),
    private val douyin: DouyinResolver = DouyinResolver(),
) {

    suspend fun resolve(text: String, userHeaders: Map<String, String> = emptyMap()): ResolvedMedia {
        val link = detectShareLink(text)
            ?: throw LinkResolveException("这既不是 B站/抖音分享链接，也不是常规的流媒体地址")
        val platform = when (link) {
            is MediaLink.Bilibili -> "B站"
            is MediaLink.Douyin -> "抖音"
        }
        PluginLog.i("链接解析", "识别到「" + platform + "」分享链接，开始解析")
        return try {
            when (link) {
                is MediaLink.Bilibili -> bilibili.resolve(link, userHeaders)
                is MediaLink.Douyin -> douyin.resolve(link, userHeaders)
            }
        } catch (e: Exception) {
            PluginLog.e("链接解析", platform + " 链接解析失败", e)
            throw e
        }
    }
}
