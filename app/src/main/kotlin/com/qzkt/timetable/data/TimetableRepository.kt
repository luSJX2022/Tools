package com.qzkt.timetable.data

import com.qzkt.timetable.jw.RawExchange
import com.qzkt.timetable.model.CourseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 课表数据仓库：负责逐周合并和查询。
 *
 * 线程模型：所有写方法都必须串行调用（由 [com.qzkt.timetable.sync.SyncScheduler] 与
 * UI 的动作协程保证），内部不做加锁。
 */
class TimetableRepository(private val store: TimetableStore) {

    private val _snapshot = MutableStateFlow(store.loadSnapshot())
    val snapshot: StateFlow<TimetableSnapshot> = _snapshot.asStateFlow()

    private var cachedDiagnostics: List<RawExchange> = store.loadDiagnostics()

    // ---------------------------------------------------------------- 写入

    /** 记录学期信息（学年学期、第一周周一、总周数）。 */
    fun setTerm(
        xnxqh: String,
        firstMonday: String,
        weekCount: Int,
        currentWeek: Int,
    ) {
        val updated = _snapshot.value.copy(
            xnxqh = xnxqh.ifBlank { _snapshot.value.xnxqh },
            firstMonday = firstMonday.ifBlank { _snapshot.value.firstMonday },
            weekCount = weekCount.coerceIn(1, 40),
            currentWeek = currentWeek,
        )
        persist(updated)
    }

    /** 用某一周的最新数据替换本地该周的内容。 */
    fun applyWeek(week: Int, fetched: List<CourseSession>) {
        val before = _snapshot.value
        val stripped = before.sessions
            .map { session -> if (week in session.weeks) session.copy(weeks = session.weeks - week) else session }
            .filter { it.weeks.isNotEmpty() }

        val fetchedForWeek = fetched.map { it.copy(weeks = listOf(week)) }
        val merged = mergeById(stripped, fetchedForWeek)

        persist(before.copy(sessions = merged.sortedWith(SESSION_ORDER), updatedAt = System.currentTimeMillis()))
    }

    /** 清空课表（保留设置）。 */
    fun clearTimetable() {
        persist(TimetableSnapshot(firstMonday = _snapshot.value.firstMonday, weekCount = _snapshot.value.weekCount))
    }

    fun recordDiagnostics(exchanges: List<RawExchange>) {
        if (exchanges.isEmpty()) return
        cachedDiagnostics = exchanges
        store.saveDiagnostics(exchanges)
    }

    fun diagnostics(): List<RawExchange> = cachedDiagnostics

    /** 存下最近一次课表页的原始 HTML，便于排查各校排版差异。 */
    fun saveRawPage(html: String) = store.saveRawPage(html)

    /**
     * 用一整份课表覆盖本地数据。
     *
     * 网页版导入会一次拿到带完整周次的课表，不像 app.do 那样按周返回，所以走这条路。
     */
    fun replaceAll(sessions: List<CourseSession>, firstMonday: String = "", weekCount: Int = 0) {
        val before = _snapshot.value
        persist(
            before.copy(
                sessions = sessions.distinctBy { it.id }.sortedWith(SESSION_ORDER),
                firstMonday = firstMonday.ifBlank { before.firstMonday },
                weekCount = if (weekCount > 0) weekCount.coerceIn(1, 40) else before.weekCount,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun persist(snapshot: TimetableSnapshot) {
        store.saveSnapshot(snapshot)
        _snapshot.value = snapshot
    }

    // ---------------------------------------------------------------- 查询

    fun sessionsInWeek(week: Int): List<CourseSession> =
        _snapshot.value.sessions.filter { week in it.weeks }

    /** 某一周某天的课，按开始节次排序。 */
    fun sessionsOn(week: Int, dayOfWeek: Int): List<CourseSession> =
        sessionsInWeek(week).filter { it.dayOfWeek == dayOfWeek }.sortedBy { it.startPeriod }

    /** 课表里出现过的最大节次，用于决定网格行数。 */
    fun maxPeriod(): Int = _snapshot.value.sessions.maxOfOrNull { it.endPeriod } ?: 12

    /** 今天是第几教学周。第一周周一未知时退回上次同步得到的周次。 */
    fun currentWeek(today: LocalDate = LocalDate.now()): Int {
        val snapshot = _snapshot.value
        val start = snapshot.firstMonday.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return snapshot.currentWeek.coerceAtLeast(1)

        val days = ChronoUnit.DAYS.between(start, today)
        val week = (Math.floorDiv(days, 7L) + 1).toInt()
        return week.coerceIn(1, snapshot.weekCount.coerceAtLeast(1))
    }

    fun firstMondayDate(): LocalDate? =
        _snapshot.value.firstMonday.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    // ---------------------------------------------------------------- 合并

    private fun mergeById(base: List<CourseSession>, incoming: List<CourseSession>): List<CourseSession> {
        val merged = LinkedHashMap<String, CourseSession>()
        (base + incoming).forEach { session ->
            val existing = merged[session.id]
            merged[session.id] = if (existing == null) {
                session
            } else {
                existing.copy(
                    weeks = (existing.weeks + session.weeks).distinct().sorted(),
                    room = session.room.ifBlank { existing.room },
                    teacher = session.teacher.ifBlank { existing.teacher },
                )
            }
        }
        return merged.values.toList()
    }

    private companion object {
        val SESSION_ORDER = compareBy<CourseSession>({ it.dayOfWeek }, { it.startPeriod }, { it.name })
    }
}
