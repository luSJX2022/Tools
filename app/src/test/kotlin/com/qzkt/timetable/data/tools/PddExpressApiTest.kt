package com.qzkt.timetable.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的快递 / 收件码」解析与鉴权的测试。
 * 夹具是真实抓包响应（拼多多取快递页面 2026-10-31 与 2026-11-14 两次抓包）。
 */
class PddExpressApiTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `解析我的快递：包裹字段与横幅`() {
        val overview = PddExpressApi.parseExpressOverview(resource("parcel/packages_ok.json"), fetchedAt = 1000L)
        // 这份夹具（2026-10-31 抓包）里只有 package_out_list，属于「近一周已取出」而不是待取件
        assertTrue("待取件列表应为空", overview.packages.isEmpty())
        assertEquals(1, overview.recentPackages.size)
        val pkg = overview.recentPackages[0]
        assertEquals("上马海都学院店", pkg.station)
        assertEquals("韵达快递", pkg.carrier)
        assertEquals("465801058426354", pkg.trackingNumber)
        assertEquals("2026-10-30 18:24:58", pkg.operateTime)
        assertTrue("应解析出顶部横幅", overview.bannerText.contains("包裹"))
        assertEquals(1000L, overview.fetchedAt)
    }

    @Test
    fun `取件码：去掉「取件码」前缀和空格`() {
        val json = """{"success":true,"result":{"combine_list":[{"package_list":[
            {"tracking_number":"465801058426354","shipping_name":"韵达快递","pick_code":"取件码 1-2-3"},
            {"tracking_number":"SF1234567890","pick_code":"8-3-0007"}]}]}}"""
        val overview = PddExpressApi.parseExpressOverview(json, 1L)
        assertEquals("1-2-3", overview.packages.first { it.trackingNumber == "465801058426354" }.pickCode)
        assertEquals("8-3-0007", overview.packages.first { it.trackingNumber == "SF1234567890" }.pickCode)
        assertEquals("A-1-2", PddExpressApi.normalizePickCode(" 取件码 A-1-2 "))
    }

    @Test
    fun `取件码：字段缺失时为空`() {
        val overview = PddExpressApi.parseExpressOverview(resource("parcel/packages_ok.json"))
        assertTrue((overview.packages + overview.recentPackages).all { it.pickCode.isEmpty() })
    }

    @Test
    fun `combine_list 里的到站包裹也要解析`() {
        val json = """{"success":true,"result":{"count":2,"station_name":"上马海都学院店","combine_list":[{"pick_code":"1-2-3","package_list":[{"tracking_number":"465801058426354","shipping_name":"韵达快递","address":"上马海都学院店","operate_time":"2026-11-15 10:00:00"},{"waybill_number":"SF1234567890","carrier":"顺丰","station_name":"驿站A"}]}],"package_out_list":[]}}"""
        val overview = PddExpressApi.parseExpressOverview(json, 1L)
        assertEquals("上马海都学院店", overview.stationName)
        assertEquals(2, overview.packages.size)
        assertTrue(overview.packages.any { it.trackingNumber == "465801058426354" && it.carrier == "韵达快递" })
        assertTrue(overview.packages.any { it.trackingNumber == "SF1234567890" && it.carrier == "顺丰" && it.station == "驿站A" })
    }

    @Test
    fun `combine_list 与 package_out_list 重复的运单号只留一条`() {
        val json = """{"success":true,"result":{"combine_list":[{"package_list":[{"tracking_number":"465801058426354","shipping_name":"韵达快递"}]}],"package_out_list":[{"tracking_number":"465801058426354","shipping_name":"韵达快递"}]}}"""
        val overview = PddExpressApi.parseExpressOverview(json, 1L)
        assertEquals(1, overview.packages.size)
        assertTrue("同一条运单不应再出现在已取出列表", overview.recentPackages.isEmpty())
    }

    @Test
    fun `package_out_list 是已取出，带图片但不进待取件`() {
        val json = """{"success":true,"result":{"combine_list":[],"package_out_list":[
            {"tracking_number":"665544332211","shipping_name":"中通快递","pic_url":"https://pfile.pddpic.com/x.png","operate_time":"2026-11-17 10:00:00"},
            {"tracking_number":"112233445566","shipping_name":"圆通快递","pic_url":"https://pfile.pddpic.com/y.png"}]}}"""
        val overview = PddExpressApi.parseExpressOverview(json, 1L)
        assertTrue("待取件应为空", overview.packages.isEmpty())
        assertEquals(2, overview.recentPackages.size)
        assertEquals("https://pfile.pddpic.com/x.png", overview.recentPackages.first { it.trackingNumber == "665544332211" }.picUrl)
    }

    @Test
    fun `解析我的快递：手机尾号与图片预览`() {
        val overview = PddExpressApi.parseExpressOverview(resource("parcel/packages_ok.json"))
        // 页面空态文案用的就是这些尾号：当前站点暂无手机尾号9196收到的快递
        assertEquals(listOf("9196"), overview.mobileTails)
        assertEquals("查看我收到的快递", overview.listPreviewDesc)
        assertEquals(3, overview.listPreviewImages.size)
    }

    @Test
    fun `解析我的快递：空列表不报错`() {
        val overview = PddExpressApi.parseExpressOverview(resource("parcel/packages_empty.json"), fetchedAt = 1L)
        assertTrue(overview.packages.isEmpty())
        assertEquals(0, overview.count)
        assertTrue(overview.bannerText.contains("包裹"))
    }

    @Test
    fun `解析收件码：code 有效期 与手机号`() {
        val code = PddExpressApi.parsePickupCode(resource("parcel/pickup_code_ok.json"), fetchedAt = 5_000L)
        assertEquals("wx202611144403567227738328459", code.code)
        assertEquals(551000L, code.expireMills)
        assertTrue(code.mobileDesc.contains("156"))
        assertEquals(550L, code.remainingSeconds(5_000L + 1_000L))
        assertEquals("9:10", code.remainingText(5_000L + 1_000L))
        assertEquals(0L, code.remainingSeconds(5_000L + 551_000L + 1L))
        assertTrue(code.remainingText(5_000L + 551_000L + 1L).contains("过期"))
    }

    @Test
    fun `鉴权：从 Cookie 或 cURL 里取 accesstoken`() {
        val cookie = "pdd_uin=ABC; pdd_user_id=7291687692272; accesstoken=TOKEN123; pdd_etag=xyz"
        val auth = PddAuth(cookie)
        assertEquals("TOKEN123", auth.accessToken)
        assertTrue(auth.ready)

        val nl = "\n"
        val fromHeader = PddAuth.fromRawText("GET /x HTTP/1.1" + nl + "Cookie: " + cookie + nl + "Host: a")
        assertEquals("TOKEN123", fromHeader.accessToken)

        val fromCurl = PddAuth.fromRawText("curl 'https://a/b' -H 'cookie: " + cookie + "' --data-raw '{}'")
        assertEquals("TOKEN123", fromCurl.accessToken)

        assertEquals("TOKEN123", PddAuth.fromRawText(cookie).accessToken)
    }

    @Test
    fun `鉴权：网页登录的 PDDAccessToken 也认`() {
        val web = PddAuth("PDDAccessToken=WEB_TOKEN; pdd_user_id=7291687692272; pdd_user_uin=abc; api_uid=x")
        assertEquals("WEB_TOKEN", web.accessToken)
        assertEquals("7291687692272", web.userId)
        assertTrue(web.ready)
    }

    @Test
    fun `鉴权：缺字段时不可用并给出提示`() {
        val noToken = PddAuth("pdd_uin=ABC; pdd_user_id=1")
        assertFalse(noToken.ready)
        assertTrue(noToken.accessToken == null)
        val error = runCatching { noToken.requireReady() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Cookie"))

        assertFalse(PddAuth("accesstoken=TOKEN").ready)
    }

    @Test
    fun `错误映射：424 和 40001 提示重新抓包`() {
        val error = PddExpressApi.errorFor(424, resource("parcel/error_424.json"))
        assertEquals(424, error.status)
        assertEquals("40001", error.errorCode)
        assertTrue(error.message!!.contains("424"))
        assertTrue(error.message!!.contains("抓"))
    }

    @Test
    fun `错误映射：success=false 也会抛异常`() {
        val failed = "{\"success\":false,\"error_code\":40001,\"error_msg\":\"\"}"
        assertTrue(runCatching { PddExpressApi.parseExpressOverview(failed, 0L) }.exceptionOrNull() is PddApiException)
        assertTrue(runCatching { PddExpressApi.parsePickupCode(failed, 0L) }.exceptionOrNull() is PddApiException)
    }
}
