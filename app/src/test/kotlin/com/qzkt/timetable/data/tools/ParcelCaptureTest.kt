package com.qzkt.timetable.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 抓包导入的解析测试：解析 → 转草稿 → 内置快照文件能读能解析。 */
class ParcelCaptureTest {

    private val sample = """
        {
          "sourceUrl": "https://mdkd.pinduoduo.com/weixin/package?entry_source=24",
          "capturedAt": "2026-10-31 04:48:13 UTC",
          "station": "上马海都学院店",
          "hint": "你有1个包裹正在运输中",
          "parcels": [
            { "name": "上马海都学院店", "trackingNumber": "465801058426354", "carrier": "韵达快递", "operateTime": "2026-10-30 18:24:58" },
            { "name": "", "trackingNumber": " SF1234567890 ", "carrier": " 顺丰 " },
            { "name": "坏数据", "trackingNumber": "12", "carrier": "" },
            { "name": "空运单号", "trackingNumber": "   ", "carrier": "" }
          ],
          "unknownField": 1
        }
    """.trimIndent()

    @Test
    fun `parse capture keeps metadata and parcels`() {
        val capture = parseParcelCapture(sample)
        assertEquals("上马海都学院店", capture.station)
        assertEquals("你有1个包裹正在运输中", capture.hint)
        assertEquals(4, capture.parcels.size)
        assertEquals("465801058426354", capture.parcels[0].trackingNumber)
        assertEquals("韵达快递", capture.parcels[0].carrier)
        assertEquals("2026-10-30 18:24:58", capture.parcels[0].operateTime)
    }

    @Test
    fun `drafts trim fields fall back to station and drop invalid numbers`() {
        val drafts = capturedToDrafts(parseParcelCapture(sample))

        // 4 条里：第 3 条运单号太短、第 4 条为空，都要被丢掉
        assertEquals(2, drafts.size)
        val sf = drafts.first { it.trackingNumber == "SF1234567890" }
        assertEquals("顺丰", sf.carrier)
        // 名称为空时用站点名兜底
        assertEquals("上马海都学院店", sf.name)
    }

    @Test
    fun `parse rejects blank or malformed payload`() {
        assertTrue(runCatching { parseParcelCapture("   ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { parseParcelCapture("{ 不是 JSON") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `bundled capture snapshot parses into one parcel`() {
        val file = File("src/main/assets/parcel/mdkd_package_capture.json")
        assertTrue("内置抓包文件应随包存在：" + file.absolutePath, file.isFile)
        val capture = parseParcelCapture(file.readText(Charsets.UTF_8))
        assertTrue("抓包应带来源地址", capture.sourceUrl.startsWith("https://mdkd.pinduoduo.com/weixin/package"))
        assertEquals("上马海都学院店", capture.station)
        val drafts = capturedToDrafts(capture)
        assertEquals("抓包里应有 1 个包裹", 1, drafts.size)
        assertEquals("465801058426354", drafts[0].trackingNumber)
        assertEquals("韵达快递", drafts[0].carrier)
    }
}
