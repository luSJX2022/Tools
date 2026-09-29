package com.qzkt.timetable.ui.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebImportSupportTest {

    // ---------------------------------------------------------- 地址整型

    @Test
    fun `去掉 app_do 接口后缀`() {
        // 这一栏是"学校地址"，但用户完全可能把完整接口地址填进去；
        // 那个地址用 WebView 打开就是白屏，必须还原成站点根路径
        assertEquals("http://jwgl.x.edu.cn", normalizeWebUrl("http://jwgl.x.edu.cn/app.do"))
        assertEquals("http://jwgl.x.edu.cn", normalizeWebUrl("http://jwgl.x.edu.cn/app.do?method=authUser&xh=1"))
        assertEquals("http://jwgl.x.edu.cn", normalizeWebUrl("http://jwgl.x.edu.cn/app.do/"))
    }

    @Test
    fun `保留正常的站点地址和子路径`() {
        assertEquals("http://jwgl.x.edu.cn", normalizeWebUrl("http://jwgl.x.edu.cn"))
        assertEquals("http://jwgl.x.edu.cn", normalizeWebUrl("  http://jwgl.x.edu.cn/  "))
        assertEquals("https://jwgl.x.edu.cn/jwgl", normalizeWebUrl("https://jwgl.x.edu.cn/jwgl/"))
    }

    @Test
    fun `只填域名时默认补 https`() {
        assertEquals("https://jwgl.x.edu.cn", normalizeWebUrl("jwgl.x.edu.cn"))
    }

    @Test
    fun `空地址返回 null 而不是空串`() {
        assertNull(normalizeWebUrl(""))
        assertNull(normalizeWebUrl("   "))
        assertNull(normalizeWebUrl("/app.do"))
    }

    // ---------------------------------------------------------- 空白页判定

    @Test
    fun `解两层 JSON`() {
        // evaluateJavascript 回传的是"JS 返回值的 JSON 编码"，
        // 而我们的 JS 返回的又是字符串，所以要解两层
        val probe = decodeProbeResult("\"{\\\"text\\\":12,\\\"frames\\\":0,\\\"title\\\":\\\"登录\\\"}\"")
        assertEquals(12, probe?.optInt("text"))
        assertEquals("登录", probe?.optString("title"))
    }

    @Test
    fun `解析不出来时返回 null 而不是抛异常`() {
        assertNull(decodeProbeResult(null))
        assertNull(decodeProbeResult(""))
        assertNull(decodeProbeResult("这不是 JSON"))
    }

    @Test
    fun `正文为空且没有 frame 才算空白页`() {
        assertTrue(isBlankPage(JSONObject("""{"text":0,"frames":0}"""), "http://jwgl.x.edu.cn"))
        assertTrue(isBlankPage(JSONObject("""{"text":-1,"frames":0}"""), "http://jwgl.x.edu.cn"))
    }

    @Test
    fun `frameset 页面没有 body 但有 frame 不算空白`() {
        assertFalse(isBlankPage(JSONObject("""{"text":0,"frames":2}"""), "http://jwgl.x.edu.cn"))
    }

    @Test
    fun `有正文就不是空白页`() {
        assertFalse(isBlankPage(JSONObject("""{"text":37,"frames":0}"""), "http://jwgl.x.edu.cn"))
    }

    @Test
    fun `about_blank 不报空白`() {
        assertFalse(isBlankPage(JSONObject("""{"text":0,"frames":0}"""), "about:blank"))
    }
}
