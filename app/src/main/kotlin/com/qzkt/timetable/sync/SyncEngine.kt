package com.qzkt.timetable.sync

import com.qzkt.timetable.data.SettingsStore
import com.qzkt.timetable.data.TimetableRepository
import com.qzkt.timetable.jw.JwAdapter
import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.JwSession
import com.qzkt.timetable.jw.JwTermInfo
import com.qzkt.timetable.jw.deriveFirstMonday
import com.qzkt.timetable.jw.qz.QzJsxsdDirect
import com.qzkt.timetable.model.SyncReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 同步走哪条路。 */
internal enum class SyncRoute {
    /** 用应用内登录留下的会话（兼容性最好）。 */
    SESSION,

    /** 走账号密码接口。 */
    PASSWORD,

    /** 没有可用的方式：跳过后台同步，前台提示重新登录。 */
    UNAVAILABLE,
}

/**
 * 选同步方式的规则。
 *
 * 提出来单独放，是因为这里踩过一次坑：手动刷新和后台定时各写各的判断，
 * 结果"有会话就用会话"只接进了后台，前台刷新还在用账号密码登录 —— 学校有反自动化校验时
 * 必然失败，用户点了刷新却什么都刷不出来。
 */
internal fun chooseRoute(hasSession: Boolean, useWebImport: Boolean): SyncRoute = when {
    hasSession -> SyncRoute.SESSION
    useWebImport -> SyncRoute.UNAVAILABLE
    else -> SyncRoute.PASSWORD
}

/**
 * 同步引擎：登录教务系统并把课表落到本地。
 *
 * UI 的"导入课表 / 下拉刷新"和后台的 [SyncWorker] 都走这里，保证两边行为一致。
 */
class SyncEngine(
    private val settingsStore: SettingsStore,
    private val repository: TimetableRepository,
    private val adapter: JwAdapter,
) {

    /** 首次导入：整学期的课。网页版一次给完，app.do 则要逐周拉。 */
    suspend fun importAll(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncReport = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val weekCount = settings.weekCount.coerceIn(1, 40)
        var session: JwSession? = null
        try {
            session = adapter.connect(JwConfig(settings.baseUrl, settings.username, settings.password))
            val term = session.loadTerm()
            applyTerm(term, weekCount, settings.firstMonday)

            // 网页版接口一次就能拿到整学期
            val wholeTerm = runCatching { session.loadWholeTerm() }.getOrNull()
            val report = if (wholeTerm != null) {
                onProgress(weekCount, weekCount)
                repository.replaceAll(wholeTerm, weekCount = weekCount)
                SyncReport(true, "导入完成，共 ${wholeTerm.size} 条课程", repository.snapshot.value.sessions.size)
            } else {
                val failures = mutableListOf<String>()
                for (week in 1..weekCount) {
                    onProgress(week - 1, weekCount)
                    runCatching { session.loadWeek(week) }
                        .onSuccess { repository.applyWeek(week, it) }
                        .onFailure { e -> failures += "第 $week 周：${e.message}" }
                }
                onProgress(weekCount, weekCount)

                val total = repository.snapshot.value.sessions.size
                when {
                    failures.size == weekCount -> SyncReport(false, "所有周次都拉取失败：${failures.first()}", total)
                    failures.isNotEmpty() ->
                        SyncReport(true, "导入完成（$total 条），有 ${failures.size} 周拉取失败：${failures.first()}", total)

                    else -> SyncReport(true, "导入完成，共 $total 条课程", total)
                }
            }

            repository.recordDiagnostics(session.rawLog)

            // 第一周周一还没确定时，从接口/推算结果里补上
            if (settings.firstMonday.isBlank() && term.firstMonday != null) {
                settingsStore.update { it.copy(firstMonday = term.firstMonday.toString()) }
            }
            if (term.currentWeek != null && term.currentWeek > 0) {
                repository.setTerm(term.xnxqh, "", weekCount, term.currentWeek)
            }
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.recordDiagnostics(session?.rawLog.orEmpty())
            SyncReport(false, e.readableMessage(), repository.snapshot.value.sessions.size)
        } finally {
            session?.close()
        }
    }

    /**
     * 定时刷新。
     *
     * app.do 只拉"当前周 + 下一周"：大多数调课只影响眼下这两周，每次全量拉 20 周既慢
     * 又容易被限流。网页版接口本来就一次返回整学期，直接整体替换并比对。
     */
    suspend fun refreshNearbyWeeks(): SyncReport = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        var session: JwSession? = null
        try {
            session = adapter.connect(JwConfig(settings.baseUrl, settings.username, settings.password))
            val term = session.loadTerm()
            applyTerm(term, settings.weekCount, settings.firstMonday)

            val weekCount = settings.weekCount.coerceIn(1, 40)
            val total = repository.snapshot.value.sessions.size

            val wholeTerm = runCatching { session.loadWholeTerm() }.getOrNull()
            if (wholeTerm != null) {
                repository.replaceAll(wholeTerm, weekCount = weekCount)
                repository.recordDiagnostics(session.rawLog)
                return@withContext SyncReport(true, "课表已更新", wholeTerm.size)
            }

            val current = term.currentWeek ?: repository.currentWeek(LocalDate.now())
            val targets = listOf(current, current + 1)
                .filter { it in 1..weekCount }
                .distinct()

            val failures = mutableListOf<String>()
            targets.forEach { week ->
                runCatching { session.loadWeek(week) }
                    .onSuccess { repository.applyWeek(week, it) }
                    .onFailure { e -> failures += "第 $week 周：${e.message}" }
            }

            repository.recordDiagnostics(session.rawLog)

            if (failures.isEmpty()) {
                SyncReport(true, "已是最新（第 $current 周）", total)
            } else {
                SyncReport(false, failures.first(), total)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncReport(false, e.readableMessage(), repository.snapshot.value.sessions.size)
        } finally {
            session?.close()
        }
    }

    /** 只做一次登录，用来在配置页验证地址与账号。 */
    suspend fun testConnection(): Result<JwTermInfo> = withContext(Dispatchers.IO) {
        var session: JwSession? = null
        try {
            val settings = settingsStore.current()
            session = adapter.connect(JwConfig(settings.baseUrl, settings.username, settings.password))
            val term = session.loadTerm()
            Result.success(term)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            repository.recordDiagnostics(session?.rawLog.orEmpty())
            session?.close()
        }
    }

    /**
     * 按当前配置挑同步方式。
     *
     * **手动刷新和后台定时都必须走这里。** 之前两边各写各的，
     * 结果"有会话就用会话"只接进了后台，前台刷新还在傻乎乎地用账号密码登录，
     * 在学校有反自动化校验时必然失败 —— 用户点了刷新却什么也刷不出来。
     *
     * @return null 表示当前没有可用的同步方式（课表来自应用内登录、但会话已经失效），
     *   调用方应当提示用户重新登录，而不是报"同步失败"。
     */
    suspend fun refresh(): SyncReport? {
        val settings = settingsStore.current()
        return when (chooseRoute(settings.hasSession, settings.useWebImport)) {
            SyncRoute.SESSION -> refreshWithSession()
            SyncRoute.UNAVAILABLE -> null
            SyncRoute.PASSWORD -> refreshNearbyWeeks()
        }
    }

    /**
     * 用**应用内登录留下的会话**刷新课表。
     *
     * 有的学校（青岛农业大学海都学院就是）登录页有反自动化校验，纯 HTTP 客户端进不去，
     * 密码登录这条路走不通。但用户在应用内登录一次之后，会话 cookie 就在手上，
     * 拿它直接拉课表页是完全够的 —— 自动同步因此照样能工作。
     */
    suspend fun refreshWithSession(): SyncReport = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        if (!settings.hasSession) {
            return@withContext SyncReport(
                false,
                "还没有在应用内登录过，无法自动更新课表",
                repository.snapshot.value.sessions.size,
            )
        }

        val weekCount = settings.weekCount.coerceIn(1, 40)
        try {
            val outcome = QzJsxsdDirect().fetchTimetable(settings.baseUrl, settings.sessionCookie)

            repository.saveRawPage(outcome.html)

            if (!outcome.ok) {
                // 首页还在、但拿不到课表：多半是会话过期了
                val expired = outcome.html.contains("userAccount") || outcome.html.contains("loginForm") ||
                    outcome.html.isBlank()
                return@withContext if (expired) {
                    SyncReport(
                        false,
                        "登录状态已过期，请打开应用重新登录一次（课表数据仍在，不会丢）",
                        repository.snapshot.value.sessions.size,
                    )
                } else {
                    SyncReport(
                        false,
                        "没能拿到课表页（试过 ${outcome.triedPaths.joinToString("、")}）",
                        repository.snapshot.value.sessions.size,
                    )
                }
            }

            repository.replaceAll(
                outcome.sessions,
                weekCount = outcome.weekCount?.takeIf { it > 0 } ?: weekCount,
            )
            outcome.currentWeek?.let { week ->
                repository.setTerm("", "", weekCount, week)
            }
            repository.recordDiagnostics(
                listOf(
                    com.qzkt.timetable.jw.RawExchange(
                        label = "定时同步（会话）${outcome.usedPath}",
                        url = settings.baseUrl + (outcome.usedPath ?: ""),
                        responseSnippet = "解析出 ${outcome.sessions.size} 条课程",
                        ok = true,
                    ),
                ),
            )

            SyncReport(true, "课表已更新", outcome.sessions.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncReport(false, e.readableMessage(), repository.snapshot.value.sessions.size)
        }
    }

    private suspend fun applyTerm(term: JwTermInfo, weekCount: Int, configuredFirstMonday: String) {
        // 开学日期有三种来源，按可靠性依次退让：
        //   ① 用户在设置页填的（最准）  ② 接口直接给的  ③ 用"今天是第几周"倒推
        // 强智网页版不给这个日期，所以第 ③ 条是日期能显示出来的关键。
        val firstMonday = configuredFirstMonday.ifBlank {
            term.firstMonday?.toString()
                ?: term.currentWeek
                    ?.takeIf { it in 1..40 }
                    ?.let { deriveFirstMonday(LocalDate.now(), it).toString() }
                    ?: ""
        }

        repository.setTerm(
            xnxqh = term.xnxqh,
            firstMonday = firstMonday,
            weekCount = term.weekCount ?: weekCount,
            currentWeek = term.currentWeek ?: repository.snapshot.value.currentWeek,
        )
    }

    private fun Exception.readableMessage(): String = when (this) {
        is JwException -> message ?: "同步失败"
        else -> "同步失败：${message ?: javaClass.simpleName}"
    }
}
