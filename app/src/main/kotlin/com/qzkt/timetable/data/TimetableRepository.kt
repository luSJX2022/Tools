package com.qzkt.timetable.data

import com.qzkt.timetable.jw.RawExchange
import com.qzkt.timetable.model.CourseSession
import com.qzkt.timetable.model.SyncChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 课表数据仓库：负责逐周合并、变更检测和查询。
 *
 * 线程模型：所有写方法都必须串行调用（由 [com.qzkt.timetable.sync.SyncScheduler] 与
 * UI 的动作协程保证），内部不做加锁。
 */
class TimetableRepository(private val store: TimetableStore) {

    private val _snapshot = MutableStateFlow(store.loadSnapshot())
    val snapshot: StateFlow<TimetableSnapshot> = _snapshot.asStateFlow()

    private val _changes = MutableStateFlow(store.loadChanges())
    val changes: StateFlow<List<SyncChange>> = _changes.asStateFlow()

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

    /**
     * 用某一周的最新数据替换本地该周的内容。
     *
     * @param detectChanges 首次全量导入时传 false，避免几百条"新增课程"通知刷屏。
     * @return 这一周检测到的变化。
     */
    fun applyWeek(week: Int, fetched: List<CourseSession>, detectChanges: Boolean): List<SyncChange> {
        val before = _snapshot.value
        val previousInWeek = before.sessions.filter { week in it.weeks }
        val changes = if (detectChanges) diffWeeks(previousInWeek, fetched) else emptyList()

        val stripped = before.sessions
            .map { session -> if (week in session.weeks) session.copy(weeks = session.weeks - week) else session }
            .filter { it.weeks.isNotEmpty() }

        val fetchedForWeek = fetched.map { it.copy(weeks = listOf(week)) }
        val merged = mergeById(stripped, fetchedForWeek)

        persist(before.copy(sessions = merged.sortedWith(SESSION_ORDER), updatedAt = System.currentTimeMillis()))

        if (changes.isNotEmpty()) {
            store.appendChanges(changes)
            _changes.value = store.loadChanges()
        }
        return changes
    }

    /** 清空课表（保留设置）。 */
    fun clearTimetable() {        persist(TimetableSnapshot(firstMonday = _snapshot.value.firstMonday, weekCount = _snapshot.value.weekCount))
        store.clearChanges()
        _changes.value = emptyList()
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
     *
     * @return 检测到的变化（首次导入时调用方可以忽略）。
     */
    fun replaceAll(sessions: List<CourseSession>, firstMonday: String = "", weekCount: Int = 0): List<SyncChange> {
        val before = _snapshot.value
        val detected = diffWeeks(before.sessions, sessions)
        persist(
            before.copy(
                sessions = sessions.distinctBy { it.id }.sortedWith(SESSION_ORDER),
                firstMonday = firstMonday.ifBlank { before.firstMonday },
                weekCount = if (weekCount > 0) weekCount.coerceIn(1, 40) else before.weekCount,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (detected.isNotEmpty()) {
            store.appendChanges(detected)
            _changes.value = store.loadChanges()
        }
        return detected
    }

    fun clearChanges() {
        store.clearChanges()
        _changes.value = emptyList()
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

    // ---------------------------------------------------------------- 变更检测

    /**
     * 比较"某一周改前 vs 改后"的课程集合。
     *
     * 先按 id 精确比对；剩下的新增/删除里，若有同名且同星期的条目，判定为调课
     * （用户看到的是"这节课换时间了"，而不是"停了一门又开了一门"）。
     */
    private fun diffWeeks(previous: List<CourseSession>, fetched: List<CourseSession>): List<SyncChange> {
        val prevById = previous.associateBy { it.id }
        val nextById = fetched.associateBy { it.id }
        val changes = mutableListOf<SyncChange>()

        nextById.forEach { (id, next) ->
            val prev = prevById[id] ?: return@forEach
            val movedParts = buildList {
                if (prev.room != next.room) add("教室 ${prev.room.ifBlank { "未填" }} → ${next.room.ifBlank { "未填" }}")
                if (prev.teacher != next.teacher) add("教师 ${prev.teacher.ifBlank { "未填" }} → ${next.teacher.ifBlank { "未填" }}")
                if (prev.startPeriod != next.startPeriod || prev.endPeriod != next.endPeriod) {
                    add("节次 ${prev.periodSpan} → ${next.periodSpan}")
                }
            }
            if (movedParts.isNotEmpty()) {
                changes += SyncChange(
                    kind = SyncChange.Kind.MODIFIED,
                    courseName = next.name,
                    dayOfWeek = next.dayOfWeek,
                    periodSpan = next.periodSpan,
                    detail = movedParts.joinToString("；"),
                )
            }
        }

        var removed = previous.filterNot { nextById.containsKey(it.id) }
        var added = fetched.filterNot { prevById.containsKey(it.id) }

        // 调课：同名同星期，只是节次/教室换了
        val movedPairs = mutableListOf<Pair<CourseSession, CourseSession>>()
        for (gone in removed.toList()) {
            val replacement = added.firstOrNull {
                it.name == gone.name && it.dayOfWeek == gone.dayOfWeek && it.teachingClass == gone.teachingClass
            }
            if (replacement != null) {
                movedPairs += gone to replacement
                removed = removed - gone
                added = added - replacement
            }
        }

        movedPairs.forEach { (gone, now) ->
            changes += SyncChange(
                kind = SyncChange.Kind.MOVED,
                courseName = now.name,
                dayOfWeek = now.dayOfWeek,
                periodSpan = now.periodSpan,
                detail = "第 ${gone.periodSpan} 节 → 第 ${now.periodSpan} 节" +
                    if (gone.room != now.room) "，教室 ${gone.room.ifBlank { "未填" }} → ${now.room.ifBlank { "未填" }}" else "",
            )
        }

        added.forEach { session ->
            changes += SyncChange(
                kind = SyncChange.Kind.ADDED,
                courseName = session.name,
                dayOfWeek = session.dayOfWeek,
                periodSpan = session.periodSpan,
                detail = "新增：${session.room.ifBlank { "教室未填" }}${if (session.teacher.isBlank()) "" else " · ${session.teacher}"}",
            )
        }

        removed.forEach { session ->
            changes += SyncChange(
                kind = SyncChange.Kind.REMOVED,
                courseName = session.name,
                dayOfWeek = session.dayOfWeek,
                periodSpan = session.periodSpan,
                detail = "取消/停课",
            )
        }

        return changes
    }

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
