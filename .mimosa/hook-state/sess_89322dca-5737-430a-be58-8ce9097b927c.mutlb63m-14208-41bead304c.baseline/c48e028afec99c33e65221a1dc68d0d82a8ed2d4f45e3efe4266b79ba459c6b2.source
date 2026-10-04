package com.qzkt.timetable.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流媒体的防盗链请求头。
 *
 * 这里只覆盖解析（纯逻辑）。真正把请求头挂到网络请求上那一步走的是 Media3 的
 * `DataSpec.withRequestHeaders`，而单元测试跑在 JVM 上、`android.net.Uri` 是空壳，
 * 造不出 `DataSpec`；那一段在真机上用一个会回显请求头的服务器验证过。
 */
class StreamHeadersTest {

    @Test
    fun `基本解析与两侧空白`() {
        val headers = parseHeaderBlock(
            """
            Referer: https://www.example.com/watch
            User-Agent:   Mozilla/5.0 (Linux; Android 16)
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "Referer" to "https://www.example.com/watch",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 16)",
            ),
            headers,
        )
    }

    @Test
    fun `头名归一成标准大小写`() {
        val headers = parseHeaderBlock("referer: https://a/\nuser-agent: UA\norigin: https://a/")
        assertEquals("https://a/", headers["Referer"])
        assertEquals("UA", headers["User-Agent"])
        assertEquals("https://a/", headers["Origin"])
        assertTrue("不该再留一份小写键", headers.keys.none { it == "referer" })
    }

    @Test
    fun `值里的冒号不会被截断`() {
        // Referer 里带端口的写法很常见，别把 https 后面那个冒号当成分隔符
        val headers = parseHeaderBlock("Referer: http://127.0.0.1:8080/video/index.m3u8")
        assertEquals("http://127.0.0.1:8080/video/index.m3u8", headers["Referer"])
    }

    @Test
    fun `注释空行与不成形的行都跳过`() {
        val headers = parseHeaderBlock(
            """
            # 这两行是注释

            这行没有冒号
            : 没有名字
            Cookie:
            Referer: https://ok/
            """.trimIndent()
        )
        assertEquals(1, headers.size)
        assertEquals("https://ok/", headers["Referer"])
    }

    @Test
    fun `同名头后面覆盖前面`() {
        val headers = parseHeaderBlock("Referer: https://old/\nreferer: https://new/")
        assertEquals("https://new/", headers["Referer"])
        assertEquals(1, headers.size)
    }

    @Test
    fun `没填头时当前值为空`() {
        StreamHeaders.clear()
        assertTrue(StreamHeaders.current.isEmpty())
    }

    @Test
    fun `后一次设置顶掉前一次`() {
        StreamHeaders.set(mapOf("Referer" to "https://a/"))
        assertEquals(mapOf("Referer" to "https://a/"), StreamHeaders.current)

        // 换一路流：旧头不能残留，否则等于把上一路的 Referer 带去了新站
        StreamHeaders.set(parseHeaderBlock("Referer: https://b/"))
        assertEquals(mapOf("Referer" to "https://b/"), StreamHeaders.current)

        // 本地文件走 clear，清干净
        StreamHeaders.clear()
        assertTrue(StreamHeaders.current.isEmpty())
    }
}
