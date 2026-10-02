package com.qzkt.timetable.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookSource
import com.qzkt.timetable.data.book.BookSources
import com.qzkt.timetable.data.book.BookStore
import com.qzkt.timetable.data.book.NlcCatalog
import com.qzkt.timetable.data.book.NlcRecord
import com.qzkt.timetable.data.book.NlcSearchPage
import com.qzkt.timetable.data.book.OnlineBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 国图检索页面的状态。 */
data class NlcUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val total: Int = 0,
    val page: Int = 0,
    val records: List<NlcRecord> = emptyList(),
    /** 会话地址前缀，翻页用。 */
    val sessionUrl: String? = null,
) {
    val pageCount: Int get() = if (total <= 0) 1 else (total + NlcSearchPage.PAGE_SIZE - 1) / NlcSearchPage.PAGE_SIZE
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page in 1 until pageCount
}

class BookViewModel(private val store: BookStore) : ViewModel() {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private val _fontSize = MutableStateFlow(18)
    val fontSize: StateFlow<Int> = _fontSize

    private val _night = MutableStateFlow(false)
    val night: StateFlow<Boolean> = _night

    /** 书城搜索状态。 */
    private val _searchResults = MutableStateFlow<List<OnlineBook>?>(null)
    val searchResults: StateFlow<List<OnlineBook>?> = _searchResults

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError

    /** 国图检索状态。 */
    private val _nlc = MutableStateFlow(NlcUiState())
    val nlc: StateFlow<NlcUiState> = _nlc

    init {
        viewModelScope.launch {
            store.books.collect { _books.value = it }
        }
        viewModelScope.launch { store.readerFontSize.collect { _fontSize.value = it } }
        viewModelScope.launch { store.readerNight.collect { _night.value = it } }
    }

    /** 书城搜书（用第一个内置在线书源）。 */
    fun search(keyword: String) {
        val source: BookSource = BookSources.byKey("quanben5")
        val query = keyword.trim()
        if (query.isEmpty() || _searching.value) return
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            runCatching { source.search(query) }
                .onSuccess { _searchResults.value = it }
                .onFailure { _searchError.value = it.message ?: "搜索失败" }
            _searching.value = false
        }
    }

    /** 书城点开一本书：入库并回调 id（拿到 id 立即跳阅读页）。 */
    fun addOnlineBook(book: OnlineBook, onAdded: (String) -> Unit) {
        viewModelScope.launch { onAdded(store.addOnlineBook(book)) }
    }

    fun addBook(name: String, uri: String) {
        viewModelScope.launch { store.addBook(name, uri) }
    }

    /** 国图检索（第一页）。 */
    fun searchNlc(keyword: String) {
        val query = keyword.trim()
        if (query.isEmpty() || _nlc.value.loading) return
        viewModelScope.launch {
            _nlc.value = NlcUiState(loading = true)
            runCatching { NlcCatalog.search(query) }
                .onSuccess { page ->
                    _nlc.value = NlcUiState(
                        total = page.total,
                        page = page.page,
                        records = page.records,
                        sessionUrl = page.sessionUrl,
                    )
                }
                .onFailure { e -> _nlc.value = NlcUiState(error = e.message ?: "检索失败") }
        }
    }

    /** 国图翻页：delta = ±1。 */
    fun nlcTurnPage(delta: Int) {
        val state = _nlc.value
        val session = state.sessionUrl ?: return
        if (state.loading) return
        val target = state.page + delta
        if (target !in 1..state.pageCount) return
        val jump = (target - 1) * NlcSearchPage.PAGE_SIZE + 1
        viewModelScope.launch {
            _nlc.value = state.copy(loading = true, error = null)
            runCatching { NlcCatalog.gotoPage(session, jump, target) }
                .onSuccess { page ->
                    _nlc.value = NlcUiState(
                        total = page.total,
                        page = page.page,
                        records = page.records,
                        sessionUrl = page.sessionUrl ?: session,
                    )
                }
                .onFailure { e -> _nlc.value = state.copy(loading = false, error = e.message ?: "翻页失败") }
        }
    }

    fun removeBook(id: String) {
        viewModelScope.launch { store.removeBook(id) }
    }

    /** 记录阅读进度（滚动时高频调用前先在内存里记，落盘交给离开页面时）。 */
    fun saveProgress(id: String, lastIndex: Int, paragraphTotal: Int) {
        viewModelScope.launch { store.saveProgress(id, lastIndex, paragraphTotal) }
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch { store.setReaderFontSize(size) }
    }

    fun setNight(night: Boolean) {
        viewModelScope.launch { store.setReaderNight(night) }
    }
}

class BookViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BookViewModel(container.bookStore) as T
}
