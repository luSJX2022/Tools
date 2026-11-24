package com.qzkt.timetable.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 全本小说网封面 URL 归一化测试。
 *
 * 线上真实数据：封面都在 img.c0m.io 图床，服务端只发叶子证书（中间 CA 是 Let's Encrypt 的
 * 过渡证书 YE1），Android 图片下载器不会 AIA 抓中间证书 → HTTPS 封面加载不出；
 * 图床实测支持 HTTP 明文，所以降级为 http。
 */
class QuanbenCoverTest {

    @Test
    fun `图床 https 封面降级为 http`() {
        assertEquals(
            "http://img.c0m.io/quanben5.com/upload/book_images/1/5.jpg",
            normalizeQuanbenCover("https://img.c0m.io/quanben5.com/upload/book_images/1/5.jpg"),
        )
    }

    @Test
    fun `图床其它子域也降级`() {
        assertEquals("http://img2.c0m.io/a.jpg", normalizeQuanbenCover("https://img2.c0m.io/a.jpg"))
        assertEquals("http://c0m.io/a.jpg", normalizeQuanbenCover("https://c0m.io/a.jpg"))
    }

    @Test
    fun `已经是 http 的不重复改`() {
        assertEquals("http://img.c0m.io/a.jpg", normalizeQuanbenCover("http://img.c0m.io/a.jpg"))
    }

    @Test
    fun `其它书源封面原样返回`() {
        assertEquals("https://example.com/cover.jpg", normalizeQuanbenCover("https://example.com/cover.jpg"))
        assertEquals("/upload/book_images/1/5.jpg", normalizeQuanbenCover("/upload/book_images/1/5.jpg"))
        // 域名只是长得像，不能被误伤
        assertEquals("https://notc0m.io/a.jpg", normalizeQuanbenCover("https://notc0m.io/a.jpg"))
        assertEquals("https://c0m.io.evil.com/a.jpg", normalizeQuanbenCover("https://c0m.io.evil.com/a.jpg"))
    }

    @Test
    fun `空值安全`() {
        assertNull(normalizeQuanbenCover(null))
        assertNull(normalizeQuanbenCover(""))
        assertNull(normalizeQuanbenCover("   "))
    }
}
