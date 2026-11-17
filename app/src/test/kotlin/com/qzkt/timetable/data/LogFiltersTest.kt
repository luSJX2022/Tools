package com.qzkt.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件日志行解析/筛选的测试（日志页「按插件筛」依赖它）。 */
class LogFiltersTest {

    private val line1 = "2026-11-17 19:00:00.123 INFO/菜鸟包裹: 请求 station/package → HTTP 200，210 字节"
    private val line2 = "2026-11-17 19:00:01.456 WARN/影视: 首页加载失败｜timeout"
    private val line3 = "2026-11-17 19:00:02.789 INFO/App: 应用进程启动"
    private val plain = "2026-11-17 19:00:03.000 一些没有级别前缀的旧日志"

    @Test
    fun `从日志行里取插件名`() {
        assertEquals("菜鸟包裹", LogFilters.pluginTagOf(line1))
        assertEquals("影视", LogFilters.pluginTagOf(line2))
        assertEquals("App", LogFilters.pluginTagOf(line3))
        assertNull(LogFilters.pluginTagOf(plain))
        assertNull(LogFilters.pluginTagOf(""))
    }

    @Test
    fun `按插件筛选`() {
        val lines = listOf(line1, line2, line3, plain)
        assertEquals(listOf(line1), LogFilters.filter(lines, "菜鸟包裹"))
        assertEquals(listOf(line2), LogFilters.filter(lines, "影视"))
        assertEquals(lines, LogFilters.filter(lines, null))
    }

    @Test
    fun `插件清单：已知顺序优先，清单外的也保留`() {
        val lines = listOf(line3, line1, line2, plain)
        val tags = LogFilters.tagsIn(lines, known = PluginLog.PLUGINS)
        assertEquals(listOf("影视", "菜鸟包裹"), tags.filter { it in PluginLog.PLUGINS })
        assertEquals(listOf("App"), tags.filterNot { it in PluginLog.PLUGINS })
    }

    @Test
    fun `崩溃堆栈算同一条日志，筛选时不会被切断`() {
        val lines = listOf(
            "2026-11-17 19:39:47.483 ERROR/Crash: 未捕获异常，线程=main",
            "java.lang.IllegalStateException: boom",
            "\tat com.qzkt.timetable.ui.anime.AnimeViewModel.loadPage(AnimeViewModel.kt:1)",
            "2026-11-17 19:39:48.000 INFO/影视: 搜索「测试」",
        )
        val entries = LogFilters.entries(lines)
        assertEquals(2, entries.size)
        assertEquals("Crash", entries[0].tag)
        assertEquals("ERROR", entries[0].level)
        assertTrue("堆栈要跟着首行", entries[0].text.contains("IllegalStateException"))
        assertTrue(entries[0].text.contains("AnimeViewModel.loadPage"))
        // 按 Crash 筛：堆栈行同样返回
        assertEquals(lines.take(3), LogFilters.filter(lines, "Crash"))
        assertEquals(1, LogFilters.filterEntries(lines, "Crash").size)
    }

    @Test
    fun `没有 tag 的旧日志单独成条`() {
        val lines = listOf(plain, line1)
        val entries = LogFilters.entries(lines)
        assertEquals(2, entries.size)
        assertNull(entries[0].tag)
        assertEquals("菜鸟包裹", entries[1].tag)
    }
}
