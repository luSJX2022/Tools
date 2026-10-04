package com.qzkt.timetable.ui.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.jw.GradeInfo
import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.qz.SmartQzAdapter
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
)

class GradesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val settings = container.settingsStore.settings.first()
            if (settings.baseUrl.isBlank() || settings.username.isBlank()) {
                _state.value = GradesUiState(error = "还没有配置教务系统账号，请先在课表页导入课表")
                return@launch
            }
            _state.value = GradesUiState(loading = true)
            try {
                val config = JwConfig(
                    baseUrl = settings.baseUrl,
                    username = settings.username,
                    password = settings.password,
                )
                val adapter = SmartQzAdapter()
                val session = adapter.connect(config)
                val grades = withContext(Dispatchers.IO) { session.loadGrades() }
                session.close()
                _state.value = GradesUiState(
                    grades = grades.orEmpty(),
                    studentName = session.studentName,
                )
            } catch (e: com.qzkt.timetable.jw.JwException) {
                _state.value = GradesUiState(error = e.message ?: "教务系统连接失败")
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
