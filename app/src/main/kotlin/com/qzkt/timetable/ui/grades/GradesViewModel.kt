package com.qzkt.timetable.ui.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.jw.GradeInfo
import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.qz.SmartQzAdapter
import com.qzkt.timetable.jw.qz.QzJsxsdDirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GradesUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val grades: List<GradeInfo> = emptyList(),
    val studentName: String? = null,
    /**
     * 是不是「该去应用内登录一次」这一类错误（学校有反自动化校验，或会话已过期）。
     *
     * 为 true 时界面除了「重试」再给一个「去应用内登录」的按钮，直接把用户送进
     * WebView 登录页 —— 不然错误文案里说的那个按钮在这个页面上根本不存在。
     */
    val needsRelogin: Boolean = false,
)

class GradesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state

    /** 用户点「查询」时才发起网络请求，不自动加载。 */
    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) {
                container.settingsStore.settings.first()
            }
            if (settings.baseUrl.isBlank() || settings.username.isBlank()) {
                _state.value = GradesUiState(error = "还没有配置教务系统账号，请先在课表页配置")
                return@launch
            }
            _state.value = GradesUiState(loading = true)

            // 有应用内登录留下的会话就先走会话：学校有反自动化校验时账号密码必败，
            // 和课表同步的选路逻辑（SyncEngine）保持一致。
            var sessionExpired = false
            if (settings.hasSession) {
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        QzJsxsdDirect().fetchGrades(settings.baseUrl, settings.sessionCookie)
                    }
                }.getOrNull()
                if (outcome?.grades?.isNotEmpty() == true) {
                    _state.value = GradesUiState(grades = outcome.grades)
                    return@launch
                }
                // 会话还在但解析不出成绩（排版不同）也留给密码路兜底；
                // 只有「被打回登录页」才记成过期，密码路也失败时用来决定提示语
                sessionExpired = outcome != null && !outcome.loggedIn
            }

            try {
                val config = JwConfig(
                    baseUrl = settings.baseUrl,
                    username = settings.username,
                    password = settings.password,
                )
                val adapter = SmartQzAdapter()
                val session = withContext(Dispatchers.IO) { adapter.connect(config) }
                val grades = withContext(Dispatchers.IO) { session.loadGrades() }
                session.close()
                _state.value = GradesUiState(
                    grades = grades.orEmpty(),
                    studentName = session.studentName,
                )
            } catch (e: com.qzkt.timetable.jw.JwException) {
                _state.value = GradesUiState(
                    error = e.message ?: "教务系统连接失败",
                    needsRelogin = e.gateBlocked || sessionExpired,
                )
            } catch (e: Exception) {
                _state.value = GradesUiState(error = e.message ?: "连接失败")
            }
        }
    }
}

class GradesViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GradesViewModel(container) as T
}
