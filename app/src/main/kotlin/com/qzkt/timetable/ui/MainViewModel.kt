package com.qzkt.timetable.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.TimetableSnapshot
import com.qzkt.timetable.data.backup.BackupData
import com.qzkt.timetable.data.backup.BackupManager
import com.qzkt.timetable.data.backup.BackupManager.Companion.json
import com.qzkt.timetable.jw.deriveFirstMonday
import com.qzkt.timetable.jw.qz.QzJsxsdDirect
import com.qzkt.timetable.jw.qz.QzWebParser
import com.qzkt.timetable.sync.SyncScheduler
import com.qzkt.timetable.widget.TimetableWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.LocalDate

/** 一次操作的即时状态，UI 直接拿它渲染进度条和提示条。 */
data class MainUiState(
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** 网页导入的一次结果，留给网页导入页面显示（Snackbar 一闪就没了，诊断信息要留得住）。 */
data class WebImportResult(
    val ok: Boolean,
    val message: String,
    val count: Int,
    /** 失败时把拿到的原始页面片段带回来，好在界面上直接看。 */
    val rawSnippet: String = "",
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.repository

    val snapshot: StateFlow<TimetableSnapshot> = repository.snapshot

    /**
     * 配置的内存镜像。
     *
     * 直接暴露 DataStore 的 Flow 会有一个小坑：写完到 Flow 重新发射之间有个空档，
     * 期间 UI 读到的还是旧值。比如配置页点了「改用网页导入」立刻跳过去，
     * WebView 拿到的 `baseUrl` 仍是空串，于是白屏。所以这里存一份同步更新的镜像。
     */
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** 当前显示第几周（用户手动翻周时变化）。 */
    private val _displayWeek = MutableStateFlow(0)
    val displayWeek: StateFlow<Int> = _displayWeek.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { _settings.value = it }
        }
        // 首次进入时把显示周次对齐到当前教学周
        viewModelScope.launch {
            if (_displayWeek.value == 0) {
                _displayWeek.value = repository.currentWeek()
            }
        }
    }

    val diagnostics: List<com.qzkt.timetable.jw.RawExchange> get() = repository.diagnostics()

    // ---------------------------------------------------------------- 周次

    fun showWeek(week: Int) {
        val max = snapshot.value.weekCount.coerceAtLeast(1)
        _displayWeek.value = week.coerceIn(1, max)
    }

    fun stepWeek(delta: Int) = showWeek(_displayWeek.value + delta)

    fun jumpToCurrentWeek() {
        _displayWeek.value = repository.currentWeek()
    }

    fun currentWeek(): Int = repository.currentWeek()

    // ---------------------------------------------------------------- 配置

    fun saveConfig(
        baseUrl: String,
        username: String,
        password: String,
        weekCount: Int,
        firstMonday: String,
    ) = viewModelScope.launch {
        persistConfig(baseUrl, username, password, weekCount, firstMonday)
    }

    /** 配置页的"测试连接"：先把当前输入落盘，再拿它去连。 */
    fun saveAndTest(
        baseUrl: String,
        username: String,
        password: String,
        weekCount: Int,
        firstMonday: String,
    ) = viewModelScope.launch {
        persistConfig(baseUrl, username, password, weekCount, firstMonday)
        testConnection()
    }

    /** 配置页的"导入课表"：先落盘再导入，避免用到上一次的旧配置。 */
    fun saveAndImport(
        baseUrl: String,
        username: String,
        password: String,
        weekCount: Int,
        firstMonday: String,
    ) = viewModelScope.launch {
        persistConfig(baseUrl, username, password, weekCount, firstMonday)
        importAll()
    }

    private suspend fun persistConfig(
        baseUrl: String,
        username: String,
        password: String,
        weekCount: Int,
        firstMonday: String,
    ) {
        val updated = _settings.value.copy(
            baseUrl = baseUrl.trim(),
            username = username.trim(),
            password = password,
            weekCount = weekCount.coerceIn(1, 40),
            firstMonday = firstMonday.trim(),
        )
        _settings.value = updated
        container.settingsStore.update { updated }
    }

    fun saveSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val updated = transform(_settings.value)
        _settings.value = updated
        container.settingsStore.update { updated }
        if (updated.configured && updated.syncEnabled) {
            SyncScheduler.schedule(container.appContext, updated.syncIntervalMinutes)
        } else {
            SyncScheduler.cancel(container.appContext)
        }
        container.classReminders.rescheduleUpcoming()
    }

    /** 导出备份（设置 + 书架 + 追番）到用户选的文件。 */
    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val payload = json.encodeToString(container.backupManager.export())
            withContext(Dispatchers.IO) {
                container.appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray())
                } ?: error("无法写入所选文件")
            }
        }.onSuccess {
            _uiState.value = _uiState.value.copy(message = "备份已导出", messageIsError = false)
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(message = "导出失败：${e.message}", messageIsError = true)
        }
    }

    /** 从备份文件恢复（覆盖本地设置、书架和追番数据）。 */
    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val text = withContext(Dispatchers.IO) {
                container.appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().decodeToString()
                } ?: error("无法读取所选文件")
            }
            val data = json.decodeFromString<BackupData>(text)
            container.backupManager.restore(data)
            // 恢复进来的设置可能改了同步开关 / 作息提醒，后台任务跟着对齐
            val restored = container.settingsStore.settings.first()
            _settings.value = restored
            if (restored.configured && restored.syncEnabled) {
                SyncScheduler.schedule(container.appContext, restored.syncIntervalMinutes)
            } else {
                SyncScheduler.cancel(container.appContext)
            }
            container.classReminders.rescheduleUpcoming()
            "已恢复 ${data.books.size} 本图书、${data.animeFavorites.size} 部收藏"
        }.onSuccess { message ->
            _uiState.value = _uiState.value.copy(message = message, messageIsError = false)
        }.onFailure { e ->
            _uiState.value = _uiState.value.copy(message = "恢复失败：${e.message}", messageIsError = true)
        }
    }

    fun clearTimetable() = viewModelScope.launch {
        repository.clearTimetable()
        _uiState.value = _uiState.value.copy(message = "已清空课表", messageIsError = false)
        TimetableWidgetUpdater.refresh(container.appContext)
    }

    /** 设置「第 1 周周一」：课表要显示具体日期全靠这个锚点。 */
    fun setFirstMonday(isoDate: String) = viewModelScope.launch {
        val current = _settings.value
        repository.setTerm(
            xnxqh = repository.snapshot.value.xnxqh,
            firstMonday = isoDate,
            weekCount = current.weekCount,
            currentWeek = repository.snapshot.value.currentWeek,
        )
        val updated = current.copy(firstMonday = isoDate)
        _settings.value = updated
        container.settingsStore.update { updated }

        container.classReminders.rescheduleUpcoming()
        TimetableWidgetUpdater.refresh(container.appContext)
        _displayWeek.value = repository.currentWeek()
        _uiState.value = _uiState.value.copy(message = "开学日期已设为 $isoDate", messageIsError = false)
    }

    // ---------------------------------------------------------------- 同步

    fun testConnection() = viewModelScope.launch {
        _uiState.value = MainUiState(busy = true, busyLabel = "正在连接教务系统…")
        val result = container.syncEngine.testConnection()
        _uiState.value = result.fold(
            onSuccess = { term ->
                val weeks = term.currentWeek?.let { "，当前第 $it 周" } ?: ""
                MainUiState(message = "连接成功：学期 ${term.xnxqh.ifBlank { "未知" }}$weeks")
            },
            onFailure = { e ->
                MainUiState(message = e.message ?: "连接失败", messageIsError = true)
            },
        )
    }

    /** 首次导入：拉整个学期。 */
    fun importAll() = viewModelScope.launch {
        _uiState.value = MainUiState(busy = true, busyLabel = "正在导入课表…")
        val report = container.syncEngine.importAll { done, total ->
            _uiState.value = _uiState.value.copy(progressDone = done, progressTotal = total)
        }
        afterSync(report.success, report.message)

        val settings = container.settingsStore.current()
        if (report.success) {
            // 接口导入成功说明这个学校走接口这条路，把"网页导入"的标记清掉，恢复后台自动同步
            container.settingsStore.update { it.copy(configured = true, useWebImport = false) }
            if (settings.syncEnabled) {
                SyncScheduler.schedule(container.appContext, settings.syncIntervalMinutes)
            }
            container.classReminders.rescheduleUpcoming()
            jumpToCurrentWeek()
        }
    }

    /** 下拉刷新 / 定时刷新：只拉当前周和下一周。 */
    fun refresh() = viewModelScope.launch {
        _uiState.value = MainUiState(busy = true, busyLabel = "正在同步…")
        val report = container.syncEngine.refresh()

        if (report == null) {
            // 课表是应用内登录导进来的，但会话没了：提示重新登录，别报"同步失败"吓人
            _uiState.value = MainUiState(
                message = "登录状态已失效，无法自动更新。请到课表右上角「教务账号 → 重新配置账号」里再在应用内登录一次。",
                messageIsError = true,
            )
            return@launch
        }

        afterSync(report.success, report.message)

        // 会话过期时把失效的 cookie 清掉：不清的话顶部横幅不会出现，
        // 用户只看到一个一闪而过的提示，不知道该去哪重新登录
        if (!report.success && report.message.contains("登录状态已过期")) {
            container.settingsStore.update { it.copy(sessionCookie = "", sessionSavedAt = 0) }
        }

        container.classReminders.rescheduleUpcoming()
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // ---------------------------------------------------------------- 网页导入

    private val _webImportResult = MutableStateFlow<WebImportResult?>(null)
    val webImportResult: StateFlow<WebImportResult?> = _webImportResult.asStateFlow()

    /** 用 WebView 里已经登录好的会话直接拉课表页（不用手动在菜单里找）。 */
    fun importWithSession(base: String, cookie: String?) = viewModelScope.launch {
        _uiState.value = MainUiState(busy = true, busyLabel = "正在用当前登录状态拉取课表…")
        val weekCount = settings.value.weekCount

        val outcome = withContext(Dispatchers.IO) {
            runCatching { QzJsxsdDirect().fetchTimetable(base, cookie) }.getOrNull()
        }

        if (outcome == null) {
            _webImportResult.value = WebImportResult(false, "拉取课表页失败（网络不通或地址不对）", 0)
            _uiState.value = MainUiState(message = "拉取课表页失败", messageIsError = true)
            return@launch
        }

        repository.saveRawPage(outcome.html)

        if (!outcome.ok) {
            // "还没登录"和"登录了但排版不认识"要分开说，否则用户会一直点同一个按钮
            val message: String
            val raw: String
            if (!outcome.loggedIn) {
                message = "看起来还没登录成功：请先在下面的页面里完成登录（能看到主界面就是成功了），然后再点一次这个按钮。"
                raw = ""
            } else {
                message = "已登录，但几个课表地址都没能解析出课程。试过：" +
                    outcome.triedPaths.joinToString("、") +
                    "。可以改用「抓取当前页面」：先在页面里进到课表查询，再回来抓取。"
                raw = outcome.html.take(6000)
            }
            _webImportResult.value = WebImportResult(ok = false, message = message, count = 0, rawSnippet = raw)
            _uiState.value = MainUiState(message = message, messageIsError = true)
            return@launch
        }

        applyImportedSessions(
            sessions = outcome.sessions,
            weekCount = outcome.weekCount?.takeIf { it > 0 } ?: weekCount,
            currentWeek = outcome.currentWeek,
            sessionCookie = cookie.orEmpty(),
            message = "已从 ${outcome.usedPath} 导入 ${outcome.sessions.size} 条课程",
        )
        _webImportResult.value = WebImportResult(
            ok = true,
            message = "已从 ${outcome.usedPath} 导入 ${outcome.sessions.size} 条课程",
            count = outcome.sessions.size,
        )
    }

    /** 网页版兜底导入：解析 WebView 抓到的页面（顶层 + 各 iframe，分开解析再合并）。 */
    fun importFromHtml(documents: List<String>) = viewModelScope.launch {
        _uiState.value = MainUiState(busy = true, busyLabel = "正在解析页面…")
        val weekCount = settings.value.weekCount
        val result = withContext(Dispatchers.Default) { QzWebParser.parseDocuments(documents, weekCount) }

        if (result.sessions.isEmpty()) {
            val reason = result.warnings.firstOrNull() ?: "没能从页面里解析出课程"
            _webImportResult.value = WebImportResult(
                ok = false,
                message = reason,
                count = 0,
                rawSnippet = documents.firstOrNull().orEmpty().take(6000),
            )
            _uiState.value = MainUiState(message = reason, messageIsError = true)
            return@launch
        }

        val warning = result.warnings.firstOrNull()?.let { "（$it）" }.orEmpty()
        val message = "已导入 ${result.sessions.size} 条课程$warning"
        applyImportedSessions(sessions = result.sessions, weekCount = weekCount, message = message)
        _webImportResult.value = WebImportResult(true, message, result.sessions.size)
    }

    private suspend fun applyImportedSessions(
        sessions: List<com.qzkt.timetable.model.CourseSession>,
        weekCount: Int,
        currentWeek: Int? = null,
        sessionCookie: String = "",
        message: String,
    ) {
        repository.replaceAll(sessions, weekCount = weekCount)

        // 网页版接口不给开学日期，但"现在是第几周"能给出来 —— 据此倒推第一周周一，
        // 课表上才有具体日期可显示（用户手动填过的不会被覆盖）
        val settingsNow = container.settingsStore.current()
        val firstMonday = settingsNow.firstMonday.ifBlank {
            currentWeek?.takeIf { it in 1..40 }
                ?.let { deriveFirstMonday(LocalDate.now(), it).toString() }
                ?: ""
        }
        if (firstMonday.isNotBlank()) {
            repository.setTerm("", firstMonday, weekCount, currentWeek ?: 0)
        }

        // 这条路是"网页版会话"，接口能不能自动同步还不知道，先按网页导入处理，
        // 免得每 6 小时推一条同步失败
        container.settingsStore.update {
            it.copy(
                configured = true,
                useWebImport = true,
                firstMonday = firstMonday,
                // 存下会话：后台定时同步就靠它，不需要密码
                sessionCookie = sessionCookie.ifBlank { it.sessionCookie },
                sessionSavedAt = if (sessionCookie.isNotBlank()) System.currentTimeMillis() else it.sessionSavedAt,
            )
        }

        val current = container.settingsStore.current()
        if (current.syncEnabled) {
            SyncScheduler.schedule(container.appContext, current.syncIntervalMinutes)
        }
        container.classReminders.rescheduleUpcoming()
        jumpToCurrentWeek()
        TimetableWidgetUpdater.refresh(container.appContext)
        _uiState.value = MainUiState(message = message)
    }

    private fun afterSync(success: Boolean, message: String) {
        _uiState.value = MainUiState(message = message, messageIsError = !success)
        TimetableWidgetUpdater.refresh(container.appContext)
    }

    // ---------------------------------------------------------------- 查询辅助

    fun sessionsForWeek(week: Int) = repository.sessionsInWeek(week)

    fun maxPeriod() = maxOf(repository.maxPeriod(), 10)

    fun firstMonday(): LocalDate? = repository.firstMondayDate()
}

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
}
