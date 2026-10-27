package com.qzkt.timetable.ui.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自定义源地址规范化：[normalizeCustomSourceUrl] 的单测。 */
class CustomSourceUrlTest {

    @Test
    fun `裸域名补协议`() {
        assertEquals("https://abc.com", normalizeCustomSourceUrl("abc.com"))
    }

    @Test
    fun `完整地址剥掉采集路径`() {
        assertEquals(
            "https://abc.com",
            normalizeCustomSourceUrl("https://abc.com/api.php/provide/vod/?ac=list"),
        )
        assertEquals(
            "http://abc.com",
            normalizeCustomSourceUrl("http://abc.com/api.php/provide/vod/"),
        )
    }

    @Test
    fun `尾斜杠和查询参数去掉`() {
        assertEquals("https://abc.com", normalizeCustomSourceUrl("https://abc.com/?t=1"))
        assertEquals("https://abc.com", normalizeCustomSourceUrl("https://abc.com/"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(normalizeCustomSourceUrl(""))
        assertNull(normalizeCustomSourceUrl("   "))
        assertNull(normalizeCustomSourceUrl("https://"))
    }
}
