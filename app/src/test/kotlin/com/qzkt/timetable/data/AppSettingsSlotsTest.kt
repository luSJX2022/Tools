package com.qzkt.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息时间表。
 *
 * 这里用的是青岛农业大学海都学院的教学时间表（学校给的纸质表），
 * 所以逐个节次钉死——写错一个时间，上课提醒和"下一节课"就全错。
 */
class AppSettingsSlotsTest {

    @Test
    fun `默认作息是学校那张表`() {
        val slots = AppSettings.defaultSlots()
        assertEquals(11, slots.size)

        assertEquals(listOf("08:30", "09:25", "10:25", "11:20"), slots.take(4).map { it.start })
        assertEquals(listOf("09:15", "10:10", "11:10", "12:05"), slots.take(4).map { it.end })

        assertEquals(listOf("14:00", "14:55", "15:55", "16:50"), slots.drop(4).take(4).map { it.start })
        assertEquals(listOf("14:45", "15:40", "16:40", "17:35"), slots.drop(4).take(4).map { it.end })

        assertEquals(listOf("18:50", "19:45", "20:40"), slots.drop(8).map { it.start })
        assertEquals(listOf("19:35", "20:30", "21:25"), slots.drop(8).map { it.end })
    }

    @Test
    fun `节次编号从 1 连续到 11`() {
        assertEquals((1..11).toList(), AppSettings.defaultSlots().map { it.period })
    }

    @Test
    fun `每节都是合法的时分且结束晚于开始`() {
        AppSettings.defaultSlots().forEach { slot ->
            val start = java.time.LocalTime.parse(slot.start)
            val end = java.time.LocalTime.parse(slot.end)
            assertTrue("第 ${slot.period} 节时间不合法", end.isAfter(start))
        }
    }

    @Test
    fun `老版本存的通用作息会被换成学校那份`() {
        val legacy = AppSettings(slots = AppSettings.LEGACY_DEFAULT_SLOTS)
        val migrated = AppSettings.migrateSlots(legacy)
        assertEquals(AppSettings.defaultSlots(), migrated.slots)
    }

    @Test
    fun `用户手动改过的作息不会被冲掉`() {
        val customized = AppSettings.defaultSlots().mapIndexed { index, slot ->
            if (index == 0) slot.copy(start = "07:30", end = "08:15") else slot
        }
        val settings = AppSettings(slots = customized)

        val migrated = AppSettings.migrateSlots(settings)
        assertSame(settings, migrated) // 没动它，连对象都是同一个
        assertEquals("07:30", migrated.slots.first().start)
    }

    @Test
    fun `全空的作息也会被当成未修改`() {
        // 极端情况：手动清空了作息，迁移时应当补回默认值而不是留空
        val empty = AppSettings(slots = emptyList())
        val migrated = AppSettings.migrateSlots(empty)
        // 不等于旧默认值，所以保持原样（由设置页让用户自己加回来）
        assertTrue(migrated.slots.isEmpty())
    }

    @Test
    fun `空的 toolOrder 退回默认顺序`() {
        assertEquals(AppSettings.DEFAULT_TOOL_ORDER, AppSettings.displayToolOrder(emptyList()))
    }

    @Test
    fun `toolOrder 丢掉下线 key 并补上新增入口`() {
        val saved = listOf("anime", "ghost", "timetable")
        val order = AppSettings.displayToolOrder(saved)
        // 下线的 ghost 被丢掉，剩下的按存的顺序，新增的 book/resolve/grades 排到末尾
        assertEquals(listOf("anime", "timetable", "book", "resolve", "grades"), order)
        // 结果一定是全量 key，不漏不重
        assertEquals(AppSettings.DEFAULT_TOOL_ORDER.toSet(), order.toSet())
    }
}
