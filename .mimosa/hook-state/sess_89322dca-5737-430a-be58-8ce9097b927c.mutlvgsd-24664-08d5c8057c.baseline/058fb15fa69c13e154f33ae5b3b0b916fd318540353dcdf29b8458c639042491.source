package com.qzkt.timetable.ui.player.link

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 解析链接时的内存 Cookie（一次解析用完就扔，不落盘）。
 *
 * 为什么需要它：B站接口会看 `buvid3` 这个 Cookie，没有就当成机器人，
 * `x/web-interface/view` 直接回一页 `<!DOCTYPE html>` 的风控页 ——
 * 现象是「解析失败」，但真实原因跟链接本身毫无关系（真机实测踩到）。
 * 所以第一次请求之前先像浏览器一样访问一次首页，把 Set-Cookie 收下来。
 *
 * 按域名匹配（[Cookie.matches]）而不是按 host 精确取：首页种在
 * `www.bilibili.com` 上的 Cookie，请求 `api.bilibili.com` 时也得带上。
 */
internal class LinkCookieJar : CookieJar {

    private val jar = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(jar) {
            cookies.forEach { cookie ->
                jar.removeAll { it.name == cookie.name && it.domain == cookie.domain }
                jar += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(jar) { jar.filter { it.matches(url) } }

    /** 接口直接把值给出来时（finger/spi 的 buvid3/4）手工塞一个。 */
    fun put(host: String, name: String, value: String) {
        if (value.isBlank()) return
        synchronized(jar) {
            jar.removeAll { it.name == name && it.domain == host }
            jar += Cookie.Builder().domain(host).path("/").name(name).value(value).build()
        }
    }

    fun has(name: String): Boolean = synchronized(jar) { jar.any { it.name == name && it.value.isNotBlank() } }

    /**
     * 拼成一个 `Cookie` 请求头的值。
     *
     * 播放/下载那一侧不走 CookieJar（ExoPlayer 用的是 HttpURLConnection，Media3 也不认 OkHttp 的 jar），
     * 所以视频 CDN 要用到的 Cookie 必须显式放进请求头里。
     */
    fun header(): String = synchronized(jar) {
        jar.filter { it.value.isNotBlank() }.joinToString("; ") { it.name + "=" + it.value }
    }
}
