package com.qzkt.timetable.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用内登录后合并多域 Cookie 的测试（纯函数，不依赖 Android WebView）。 */
class ParcelLoginCookieTest {

    @Test
    fun `同名 Cookie 以后面的域为准`() {
        val merged = mergeCookieStrings(
            "pdd_user_id=7291; accesstoken=OLD",
            "jrpl=x; accesstoken=NEW",
            null,
        )
        assertTrue(merged.contains("pdd_user_id=7291"))
        assertTrue(merged.contains("jrpl=x"))
        assertTrue(merged.contains("accesstoken=NEW"))
        assertFalse("旧值应被覆盖", merged.contains("accesstoken=OLD"))
    }

    @Test
    fun `空段与空值被丢掉`() {
        assertEquals("", mergeCookieStrings(null, "   ", ""))
        assertEquals("a=1", mergeCookieStrings("a=", " a=1 ; "))
        assertEquals("a=1; b=2", mergeCookieStrings("a=1", "b=2"))
    }

    @Test
    fun `合并结果能被鉴权识别`() {
        val merged = mergeCookieStrings("pdd_uin=U; pdd_user_id=1", "accesstoken=TOKEN")
        val auth = com.qzkt.timetable.data.tools.PddAuth(merged)
        assertEquals("TOKEN", auth.accessToken)
        assertTrue(auth.ready)
    }
}
