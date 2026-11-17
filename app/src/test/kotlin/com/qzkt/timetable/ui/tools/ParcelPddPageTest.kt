package com.qzkt.timetable.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内嵌拼多多页面地址的测试：路径写错会 302 到微信 OAuth 或直接 404，必须锁住。 */
class ParcelPddPageTest {

    @Test
    fun `取快递页必须走非微信路由`() {
        assertTrue(PDD_HOME_URL.startsWith("https://mdkd.pinduoduo.com/"))
        // 带 /weixin/ 的路由会被 302 到 open.weixin.qq.com，WebView 里打不开
        assertFalse(PDD_HOME_URL.contains("/weixin/"))
        assertTrue(PDD_HOME_URL.contains("package"))
    }

    @Test
    fun `登录页用拼多多网页登录`() {
        assertTrue(PDD_LOGIN_URL.startsWith("https://mobile.yangkeduo.com/"))
        assertTrue(PDD_LOGIN_URL.contains("login"))
    }
}
