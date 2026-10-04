package com.qzkt.timetable.ui.player.link

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 的域名匹配：B站的 buvid3 种在 www 上，请求 api 子域时也必须带上，
 * 否则风控照样回 HTML。
 */
class LinkCookieJarTest {

    /** 不能用 Cookie.parse：JVM 单测里没有 Android 那份 public suffix 资源，会直接抛异常。 */
    private fun cookie(domain: String, name: String, value: String): Cookie =
        Cookie.Builder().domain(domain).path("/").name(name).value(value).build()

    @Test
    fun `www 上种的 cookie 也会发给 api 子域`() {
        val jar = LinkCookieJar()
        val www = "https://www.bilibili.com/".toHttpUrl()
        jar.saveFromResponse(www, listOf(cookie("bilibili.com", "buvid3", "abc")))

        val api = "https://api.bilibili.com/x/web-interface/view?bvid=BV1".toHttpUrl()
        assertEquals(listOf("buvid3"), jar.loadForRequest(api).map { it.name })
        assertTrue(jar.has("buvid3"))
        // 别的站不该拿到
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `指纹接口塞进来的 cookie 会发给子域`() {
        val jar = LinkCookieJar()
        jar.put("bilibili.com", "buvid3", "SPI3")
        jar.put("bilibili.com", "buvid4", "SPI4")

        val api = "https://api.bilibili.com/x/frontend/finger/spi".toHttpUrl()
        assertEquals(listOf("buvid3", "buvid4"), jar.loadForRequest(api).map { it.name }.sorted())
        assertFalse(jar.has("buvid5"))
    }
}
