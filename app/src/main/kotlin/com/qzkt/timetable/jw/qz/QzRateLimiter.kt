package com.qzkt.timetable.jw.qz

/**
 * 教务网关的**全局限速器**。
 *
 * 考试查询、成绩查询、课表同步打的是同一个强智网关（海都学院实测），学校对
 * 「同一会话短时间内多次请求」非常敏感：超量会直接注销登录并返回
 * 「您操作系统过于频繁，已被注销登录，正常使用请勿高频操作！」。
 * 所以这三条路必须共用一个闸门，而不是各自控制节奏。
 *
 * 做法是在 [QzHttp] 的客户端上装一个拦截器，任何请求发出去之前先 [acquire]。
 * 拦在客户端的最大好处是：以后新增的教务接口自动受控，不需要每个调用点都记得加。
 */
object QzRateLimiter {

    /** 相邻两次请求的最小间隔（毫秒）。 */
    const val MIN_INTERVAL_MS: Long = 1_000L

    /** 一旦被学校判定高频，静默这么久再允许发请求（单位毫秒）。 */
    const val QUIET_WINDOW_MS: Long = 10 * 60_000L

    /** 上一次真正发出请求的时刻。 */
    @Volatile
    private var lastRequestAt: Long = 0L

    /** 被限流后的静默截止时刻。 */
    @Volatile
    private var quietUntil: Long = 0L

    /** 本次限制实际生效的次数（诊断用）。 */
    @Volatile
    var throttleCount: Long = 0L
        private set

    /**
     * 还要等多久才允许发请求。拆出来是因为它可以纯函数式测试，不必真的 sleep。
     *
     * @param now 当前时刻
     * @param lastAt 上次请求时刻（0 表示还没发过）
     * @param interval 最小间隔
     * @param quiet 静默截止时刻（0 表示没有静默）
     */
    fun waitMillis(
        now: Long,
        lastAt: Long,
        interval: Long = MIN_INTERVAL_MS,
        quiet: Long = 0L,
    ): Long {
        var wait = 0L
        if (lastAt > 0) {
            val elapsed = now - lastAt
            if (elapsed in 0 until interval) wait = interval - elapsed
        }
        if (quiet > now) {
            val quietWait = quiet - now
            if (quietWait > wait) wait = quietWait
        }
        return wait
    }

    /** 阻塞到可以发请求为止。调用方是 IO 线程，短暂阻塞可以接受。 */
    @Synchronized
    fun acquire() {
        val wait = waitMillis(System.currentTimeMillis(), lastRequestAt, MIN_INTERVAL_MS, quietUntil)
        if (wait > 0) {
            throttleCount += 1
            runCatching { Thread.sleep(wait) }
        }
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * 发现页面是「操作过于频繁」时调这个：进入静默期，期间所有教务请求都会等
     * （实际效果是拿不到数据就快速返回，见 [inQuietPeriod]）。
     */
    @Synchronized
    fun noteRateLimited(now: Long = System.currentTimeMillis()) {
        quietUntil = now + QUIET_WINDOW_MS
    }

    /** 现在是否处在静默期（被限流过）。 */
    fun inQuietPeriod(now: Long = System.currentTimeMillis()): Boolean = quietUntil > now

    /** 静默期还剩多少毫秒。 */
    fun quietRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (quietUntil - now).coerceAtLeast(0L)

    /** 测试用：把状态清干净。 */
    internal fun resetForTest() {
        lastRequestAt = 0L
        quietUntil = 0L
        throttleCount = 0L
    }
}
