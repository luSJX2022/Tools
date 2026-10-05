package com.qzkt.timetable.ui.player.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享链接识别：用真实分享文案的形态测，不联网。
 */
class MediaLinkTest {

    @Test
    fun `从分享文案里抠出短链`() {
        val text = "【标题】 https://b23.tv/AbCd12 复制此链接，打开【哔哩哔哩】App观看"
        assertEquals("https://b23.tv/AbCd12", extractShareUrl(text))
        assertTrue(detectShareLink(text) is MediaLink.Bilibili)
    }

    @Test
    fun `地址末尾的中英文标点都要切掉`() {
        assertEquals("https://b23.tv/abc", extractShareUrl("看看这个 https://b23.tv/abc，很好笑"))
        assertEquals("https://b23.tv/abc", extractShareUrl("https://b23.tv/abc。"))
        assertEquals("https://b23.tv/abc", extractShareUrl("(https://b23.tv/abc)"))
    }

    @Test
    fun `没有协议头也能认`() {
        val link = detectShareLink("www.bilibili.com/video/BV1GJ411x7h7")
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", link?.url)
    }

    @Test
    fun `BV号与分P`() {
        val link = detectShareLink("https://www.bilibili.com/video/BV1GJ411x7h7?p=3&t=12") as MediaLink.Bilibili
        assertEquals("BV1GJ411x7h7", link.bvid)
        assertEquals(3, link.page)
    }

    @Test
    fun `av号与番剧`() {
        val av = detectShareLink("https://www.bilibili.com/video/av170001") as MediaLink.Bilibili
        assertEquals(170001L, av.aid)
        assertNull(av.bvid)

        val ep = detectShareLink("https://www.bilibili.com/bangumi/play/ep123456") as MediaLink.Bilibili
        assertEquals(123456L, ep.epId)

        val ss = detectShareLink("https://www.bilibili.com/bangumi/play/ss28747") as MediaLink.Bilibili
        assertEquals(28747L, ss.ssId)
        assertEquals(1, ss.page)
    }

    @Test
    fun `短链认不出id时不算失败`() {
        val link = detectShareLink("https://b23.tv/xyz789") as MediaLink.Bilibili
        assertNull(link.bvid)
        assertNull(link.aid)
    }

    @Test
    fun `图文动态与专栏链接`() {
        val opus = detectShareLink("https://www.bilibili.com/opus/755822555336540166") as MediaLink.Bilibili
        assertEquals(755822555336540166L, opus.opusId)

        // t.bilibili.com 是动态的老分享域：路径本身只有一串数字
        val legacy = detectShareLink("https://t.bilibili.com/755822555336540166?tab=2") as MediaLink.Bilibili
        assertEquals(755822555336540166L, legacy.opusId)

        val cv = detectShareLink("https://www.bilibili.com/read/cv43606912") as MediaLink.Bilibili
        assertEquals(43606912L, cv.cvId)
    }

    @Test
    fun `抖音的几种地址`() {
        val short = detectShareLink("7.68 复制打开抖音，看看【某某】的作品 https://v.douyin.com/iRabcdef/") as MediaLink.Douyin
        assertNull(short.awemeId)

        val web = detectShareLink("https://www.douyin.com/video/7123456789012345678") as MediaLink.Douyin
        assertEquals("7123456789012345678", web.awemeId)

        val share = detectShareLink("https://www.iesdouyin.com/share/video/7123456789012345678/?region=CN") as MediaLink.Douyin
        assertEquals("7123456789012345678", share.awemeId)

        val modal = detectShareLink("https://www.douyin.com/discover?modal_id=7123456789012345678") as MediaLink.Douyin
        assertEquals("7123456789012345678", modal.awemeId)
    }

    @Test
    fun `抖音音乐链接`() {
        val web = detectShareLink("https://www.douyin.com/music/7596294695140919334") as MediaLink.Douyin
        assertEquals("7596294695140919334", web.musicId)
        assertNull(web.awemeId)

        val share = detectShareLink("https://www.iesdouyin.com/share/music/7596294695140919334/") as MediaLink.Douyin
        assertEquals("7596294695140919334", share.musicId)
        assertNull(share.awemeId)

        // 视频地址不能被误认成音乐
        val video = detectShareLink("https://www.douyin.com/video/7123456789012345678") as MediaLink.Douyin
        assertNull(video.musicId)
    }

    @Test
    fun `普通流地址不当作分享链接`() {
        assertNull(detectShareLink("https://example.com/live/index.m3u8"))
        assertNull(detectShareLink("http://192.168.1.10:8080/movie.mp4"))
        assertNull(detectShareLink("随便一段文字"))
    }
}
