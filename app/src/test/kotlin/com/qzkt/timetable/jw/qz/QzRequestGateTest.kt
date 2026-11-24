package com.qzkt.timetable.jw.qz

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求去重：同一个 GET 在短时间内只发一次。
 * 线上教训：连点查询 / 进出页面会重复触发同样的请求，白占用学校网关的频控配额。
 */
class QzRequestGateTest {

    private fun get(url: String, cookie: String? = null): Request =
        Request.Builder().url(url).apply {
            if (cookie != null) header("Cookie", cookie)
        }.build()

    @Test
    fun `只有 GET 参与去重`() {
        val get = get("https://jw.hdxy.edu.cn/jsxsd/xsks/xsksap_list")
        assertTrue(QzRequestGate.keyOf(get)!!.startsWith("https://jw.hdxy.edu.cn"))
        val post = Request.Builder()
            .url("https://jw.hdxy.edu.cn/jsxsd/xsks/xsksap_list")
            .post("xnxq01id=2026-2027-1".toRequestBody(null))
            .build()
        assertNull("POST 有副作用，不能去重", QzRequestGate.keyOf(post))
    }

    @Test
    fun `不同会话不共用结果`() {
        val a = QzRequestGate.keyOf(get("https://jw.x/a", "JSESSIONID=1"))
        val b = QzRequestGate.keyOf(get("https://jw.x/a", "JSESSIONID=2"))
        assertFalse("不同 Cookie 必须是不同键", a == b)
        val c = QzRequestGate.keyOf(get("https://jw.x/a", "JSESSIONID=1"))
        assertEquals("同 URL + 同 Cookie 才是同一个请求", a, c)
    }

    @Test
    fun `缓存窗口很短`() {
        val t = 10_000L
        assertTrue(QzRequestGate.cacheFresh(t + 500, t))
        assertTrue(QzRequestGate.cacheFresh(t + QzRequestGate.CACHE_TTL_MS, t))
        assertFalse("过期就别复用", QzRequestGate.cacheFresh(t + QzRequestGate.CACHE_TTL_MS + 1, t))
        assertFalse("时间倒流也不能用", QzRequestGate.cacheFresh(t - 1, t))
    }

    @Test
    fun `no-cache 标记能绕过缓存`() {
        // 成绩/考试有「空页重试一遍」的逻辑，重试必须真的打网络
        val plain = get("https://jw.x/a", "JSESSIONID=1")
        val noCache = Request.Builder()
            .url("https://jw.x/a")
            .header("Cookie", "JSESSIONID=1")
            .header(QzRequestGate.NO_CACHE_HEADER, "1")
            .build()
        assertTrue(QzRequestGate.cacheable(plain))
        assertFalse(QzRequestGate.cacheable(noCache))
        // 键本身不含这个标记：它只影响是否用缓存，不改变「这是同一个资源」
        assertEquals(QzRequestGate.keyOf(plain), QzRequestGate.keyOf(noCache))
    }
}
