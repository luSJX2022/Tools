package com.qzkt.timetable.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 深链拦截的测试：真机上拼多多登录页会跳 pinduoduo://，WebView 打不开会白屏。 */
class ParcelLoginSchemeTest {

    @Test
    fun `拼多多等自定义 scheme 视为深链`() {
        assertTrue(isDeepLinkScheme("pinduoduo://com.xunmeng.pinduoduo/index.html"))
        assertTrue(isDeepLinkScheme("weixin://dl/business/?t=abc"))
        assertTrue(isDeepLinkScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(isDeepLinkScheme("alipays://platformapi/startapp"))
    }

    @Test
    fun `网页协议与相对地址不算深链`() {
        assertFalse(isDeepLinkScheme("https://mobile.yangkeduo.com/login.html"))
        assertFalse(isDeepLinkScheme("http://example.com"))
        assertFalse(isDeepLinkScheme("about:blank"))
        assertFalse(isDeepLinkScheme("data:text/html,hi"))
        assertFalse(isDeepLinkScheme("/relative/path"))
        assertFalse(isDeepLinkScheme(""))
    }
}
