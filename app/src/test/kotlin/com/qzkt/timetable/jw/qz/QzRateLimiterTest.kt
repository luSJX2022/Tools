package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务网关全局限速器：考试 / 成绩 / 课表共用同一个闸门。
 *
 * 线上教训：三条路同时打到强智网关时，学校会直接注销登录并返回
 * 「您操作系统过于频繁，已被注销登录」，所以要保证相邻请求至少隔 1 秒。
 */
class QzRateLimiterTest {

    @Test
    fun `第一次请求不用等`() {
        assertEquals(0L, QzRateLimiter.waitMillis(now = 1_000L, lastAt = 0L))
    }

    @Test
    fun `相邻请求补足最小间隔`() {
        // 刚发完立刻再发：要等满 1 秒
        assertEquals(1_000L, QzRateLimiter.waitMillis(now = 10_000L, lastAt = 10_000L))
        // 过了 400 毫秒：还差 600
        assertEquals(600L, QzRateLimiter.waitMillis(now = 10_400L, lastAt = 10_000L))
        // 已经过了 1 秒以上：不用等
        assertEquals(0L, QzRateLimiter.waitMillis(now = 11_500L, lastAt = 10_000L))
    }

    @Test
    fun `静默期优先于最小间隔`() {
        val quietUntil = 20_000L
        // 静默期还剩 5 秒，即使距上次请求很久也要等到静默结束
        assertEquals(5_000L, QzRateLimiter.waitMillis(now = 15_000L, lastAt = 10_000L, quiet = quietUntil))
        // 静默期已过，按最小间隔来
        assertEquals(0L, QzRateLimiter.waitMillis(now = 21_000L, lastAt = 10_000L, quiet = quietUntil))
    }

    @Test
    fun `记录限流后进入静默期`() {
        QzRateLimiter.resetForTest()
        val now = 1_000_000L
        assertFalse(QzRateLimiter.inQuietPeriod(now))
        QzRateLimiter.noteRateLimited(now)
        assertTrue(QzRateLimiter.inQuietPeriod(now))
        assertEquals(QzRateLimiter.QUIET_WINDOW_MS, QzRateLimiter.quietRemainingMs(now))
        // 静默期内任何请求都要等到窗口结束（quiet 参数要显式传，默认 0 表示没有静默）
        assertEquals(
            QzRateLimiter.QUIET_WINDOW_MS,
            QzRateLimiter.waitMillis(now, lastAt = now, quiet = now + QzRateLimiter.QUIET_WINDOW_MS),
        )
        assertFalse(QzRateLimiter.inQuietPeriod(now + QzRateLimiter.QUIET_WINDOW_MS + 1))
        QzRateLimiter.resetForTest()
    }

    @Test
    fun `最小间隔是一秒`() {
        assertEquals(1_000L, QzRateLimiter.MIN_INTERVAL_MS)
    }
}
