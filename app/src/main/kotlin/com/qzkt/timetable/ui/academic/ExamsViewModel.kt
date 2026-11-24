package com.qzkt.timetable.ui.academic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.jw.ExamInfo
import com.qzkt.timetable.jw.qz.QzHttp
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter
import com.qzkt.timetable.jw.qz.QzJsxsdAutoLogin
import com.qzkt.timetable.jw.qz.QzJsxsdDirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExamsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val exams: List<ExamInfo> = emptyList(),
    /** 是否已经查过一次：用来区分「还没点查询」和「查了但没有考试」。 */
    val hasLoaded: Boolean = false,
    /** 是不是「该去应用内登录一次」这一类错误。 */
    val needsRelogin: Boolean = false,
    /** 教务返回页面的摘要：没解析出考试时给「查看原始页面」用，便于排查页面改版。 */
    val pageSnippet: String = "",
    /** 学校系统把我们当高频请求、强制注销了登录。 */
    val rateLimited: Boolean = false,
)

/**
 * 考试安排页。
 *
 * 走的是**应用内登录留下的会话 cookie** 这条路（和成绩查询同一套）：
 * 海都学院这类学校的登录页有反自动化校验，账号密码这条路基本走不通，
 * 但用户在课表页「在应用内登录」一次之后，考试安排页用同一个会话就能直接拉。
 * 会话过期时再用账号密码自动登录换一个新会话，换到了就续进设置。
 */
class ExamsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ExamsUiState())
    val state: StateFlow<ExamsUiState> = _state

    fun refresh() {
        if (_state.value.loading) return

        // 被学校限流过就歇一会儿：立刻重试只会再被注销一次，而且注销后要重新登录才能恢复。
        val limitedAgo = System.currentTimeMillis() - Cooldown.rateLimitedAt
        if (Cooldown.rateLimitedAt > 0 && limitedAgo < Cooldown.BAN_WINDOW_MS) {
            val minutes = ((Cooldown.BAN_WINDOW_MS - limitedAgo) / 60_000).coerceAtLeast(1)
            _state.value = ExamsUiState(
                hasLoaded = true,
                error = "刚才被教务系统判定为操作过于频繁，已经把登录注销了。请先「去应用内登录」重新登录，" +
                    "并等大约 " + minutes + " 分钟再查，免得又被踢。",
                needsRelogin = true,
                rateLimited = true,
            )
            return
        }
        // 连点防护：两次查询至少隔 15 秒。
        val sinceLast = System.currentTimeMillis() - Cooldown.lastQueryAt
        if (Cooldown.lastQueryAt > 0 && sinceLast < Cooldown.MIN_INTERVAL_MS) {
            return
        }
        Cooldown.lastQueryAt = System.currentTimeMillis()

        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { container.settingsStore.settings.first() }
            if (settings.baseUrl.isBlank() || settings.username.isBlank()) {
                _state.value = ExamsUiState(
                    error = "还没有配置教务系统账号，请先在课表页配置",
                    hasLoaded = true,
                )
                return@launch
            }
            _state.value = ExamsUiState(loading = true)

            var sessionExpired = false
            var pageMissing = false
            var rateLimited = false
            var snippet = ""

            if (settings.hasSession) {
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        QzJsxsdDirect().fetchExams(settings.baseUrl, settings.sessionCookie)
                    }
                }.getOrNull()
                if (outcome != null && outcome.exams.isNotEmpty()) {
                    _state.value = ExamsUiState(
                        exams = outcome.exams.sortedBy { it.sortKey },
                        hasLoaded = true,
                        pageSnippet = outcome.pageSnippet,
                    )
                    return@launch
                }
                snippet = outcome?.pageSnippet.orEmpty()
                rateLimited = outcome?.rateLimited == true
                if (outcome?.rateLimited == true) Cooldown.rateLimitedAt = System.currentTimeMillis()
                sessionExpired = outcome != null && !outcome.loggedIn
                pageMissing = outcome != null && outcome.loggedIn && outcome.usedPath == null
            }

            // 会话过期：用账号密码自动登录换新会话（新一代强智登录纯 HTTP 能过）
            val autoCookie = if (settings.username.isNotBlank() && settings.password.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        QzJsxsdAutoLogin.login(
                            QzHttp.defaultClient(),
                            QzJsxsdAdapter.resolveBase(settings.baseUrl),
                            settings.username,
                            settings.password,
                        )
                    }
                }.getOrNull()
            } else {
                null
            }
            if (autoCookie != null) {
                val outcome = runCatching {
                    withContext(Dispatchers.IO) { QzJsxsdDirect().fetchExams(settings.baseUrl, autoCookie) }
                }.getOrNull()
                if (outcome != null && outcome.exams.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        container.settingsStore.update {
                            it.copy(sessionCookie = autoCookie, sessionSavedAt = System.currentTimeMillis())
                        }
                    }
                    _state.value = ExamsUiState(
                        exams = outcome.exams.sortedBy { it.sortKey },
                        hasLoaded = true,
                        pageSnippet = outcome.pageSnippet,
                    )
                    return@launch
                }
                if (outcome != null) snippet = outcome.pageSnippet
                if (outcome != null && outcome.loggedIn && outcome.usedPath == null) pageMissing = true
                if (outcome != null && !outcome.loggedIn) sessionExpired = true
            }

            // 登录态是好的、页面也找到了，就是没有考试记录 —— 这不算错误，
            // 放假 / 还没排考的时候本来就该是空的。
            _state.value = ExamsUiState(
                exams = emptyList(),
                hasLoaded = true,
                needsRelogin = sessionExpired || rateLimited,
                pageSnippet = snippet,
                rateLimited = rateLimited,
                error = when {
                    // 这一条必须和「会话过期」分开说：是查询太密被学校踢了，重新登录就能恢复，
                    // 而且要提醒用户别反复点，否则又会立刻被踢。
                    rateLimited -> "教务系统提示操作过于频繁，已经把你的登录注销了。请点「去应用内登录」重新登录，" +
                        "之后隔一会儿再查考试（别连续点重试）。"
                    sessionExpired -> "登录状态已失效，考试安排查不到。请在页面里登录一次再试。"
                    pageMissing -> "没找到教务系统里的考试安排页面，可能这所学校没开这个入口。"
                    else -> null
                },
            )
        }
    }
}

/**
 * 查询节流。放在 object 里（而不是 ViewModel 字段）是因为离开页面再进来会新建 ViewModel，
 * 实例字段挡不住「反复进页面」这种情况。
 */
private object Cooldown {
    /** 两次查询的最小间隔：学校对高频操作很敏感。 */
    const val MIN_INTERVAL_MS = 15_000L
    /** 被限流后的静默期。 */
    const val BAN_WINDOW_MS = 10 * 60_000L
    var lastQueryAt: Long = 0
    var rateLimitedAt: Long = 0
}

class ExamsViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ExamsViewModel(container) as T
}
