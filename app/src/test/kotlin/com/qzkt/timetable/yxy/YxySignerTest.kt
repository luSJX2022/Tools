package com.qzkt.timetable.yxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

class YxySignerTest {

    @Test
    fun `签名与文档中的开源实现样例一致`() {
        val params = mapOf(
            "appVersion" to "730",
            "deviceId" to "ym-57766d45173c643469eadb10a8766666",
            "nt" to "1756225768826",
            "platform" to "YUNMA_APP",
            "sceneCode" to "app_user_login",
            "schoolCode" to "10337",
        )

        assertEquals(
            "i85zWFEF0nb8BR8VxQGw7IqkZvT9C5CUqyt6KteO+4U=",
            YxySigner.sign(params),
        )
    }

    @Test
    fun `签名跳过空值并按 key 排序`() {
        val first = mapOf("c" to "three", "a" to "one", "b" to "")
        val second = mapOf("a" to "one", "c" to "three")

        assertEquals(YxySigner.sign(first), YxySigner.sign(second))
    }

    @Test
    fun `验证码登录响应没有 token 仍然可以创建校园卡会话`() {
        val session = sessionFromLoginResponse(
            body = JSONObject("""{"statusCode":0,"success":true,"data":{"id":"user-123","bindCardStatus":1}}"""),
            tokenHeader = null,
            deviceId = "ym-device",
            phone = "13800000000",
            schoolCode = "13997",
        )

        assertEquals("user-123", session.ymId)
        assertEquals("", session.token)
        assertEquals("13997", session.schoolCode)
    }

    @Test
    fun `验证码登录响应仍然读取服务端返回的 token`() {
        val session = sessionFromLoginResponse(
            body = JSONObject("""{"data":{"id":"user-123","token":"body-token"}}"""),
            tokenHeader = "header-token",
            deviceId = "ym-device",
            phone = "13800000000",
            schoolCode = "13997",
        )

        assertEquals("body-token", session.token)
    }

    @Test
    fun `宿舍授权支持绝对跳转地址和路由 hash 中的授权码`() {
        val authUrl = "https://auth.xiaofubao.com/authoriz/getCodeV2".toHttpUrl()

        assertEquals(
            "code-123",
            extractElectricityAuthCode(
                authUrl,
                "https://application.xiaofubao.com/#/?ymCode=code-123",
            ),
        )
    }

    @Test
    fun `宿舍授权支持相对跳转地址`() {
        val authUrl = "https://auth.xiaofubao.com/authoriz/getCodeV2".toHttpUrl()

        assertEquals(
            "relative-code",
            extractElectricityAuthCode(authUrl, "/callback?ymCode=relative-code"),
        )
    }

    @Test
    fun `宿舍绑定入口打开学校官方缴费页面且不保留临时授权码`() {
        val url = YxyClient().schoolDormPaymentUrl().toHttpUrl()

        assertEquals("nygl.hdxy.edu.cn", url.host)
        assertEquals("/cpp/mobileNew/pay.html", url.encodedPath)
        assertEquals(null, url.queryParameter("code"))
    }

    @Test
    fun `MGS余额只读取明确的剩余电量字段`() {
        val meters = parseElectricityMeters(
            JSONObject("""{"data":{"surplus":"12.5","soc":"999"}}"""),
            "mgs",
        )

        assertEquals(1, meters.size)
        assertEquals("12.5", meters.single().remainingKwh.toString())
        assertEquals("room", meters.single().mdtype)
    }

    @Test
    fun `ZHPF表计保留类型且不把房间余额复制成表计余额`() {
        val meters = parseElectricityMeters(
            JSONObject(
                """{"data":{"soc":88,"surplusList":[{"mdtype":"01","mdname":"照明"},{"mdtype":"02","surplus":"6.25"}]}}""",
            ),
            "zhpf",
        )

        assertEquals(2, meters.size)
        assertEquals("01", meters[0].mdtype)
        assertNull(meters[0].remainingKwh)
        assertEquals("6.25", meters[1].remainingKwh.toString())
    }

    @Test
    fun `剩余天数只在最近七个完整自然日均有用量时估算`() {
        val today = LocalDate.of(2025, 1, 8)
        val records = (1..7).map { day ->
            YxyUsageRecord("2025-01-${day.toString().padStart(2, '0')}", "2")
        }

        val estimate = estimateElectricityDays(14.0, records, today)

        assertEquals(2.0, estimate?.averageDailyKwh)
        assertEquals(7, estimate?.remainingDays)
        assertNull(estimateElectricityDays(14.0, records.dropLast(1), today))
        assertNull(estimateElectricityDays(null, records, today))
    }

    @Test
    fun `手动房间API ID必须完整且会移除首尾空格`() {
        val room = createManualElectricityRoom(" area ", " building ", " floor ", " room ", "")

        assertEquals("area", room.areaId)
        assertEquals("area-building-floor-room", room.displayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `手动房间API ID不可缺失`() {
        createManualElectricityRoom("area", "building", "", "room", "")
    }
}
