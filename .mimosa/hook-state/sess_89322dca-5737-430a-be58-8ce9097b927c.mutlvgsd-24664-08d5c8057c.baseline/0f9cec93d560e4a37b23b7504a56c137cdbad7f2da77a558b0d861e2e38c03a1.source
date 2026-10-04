package com.qzkt.timetable.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookSource
import com.qzkt.timetable.data.book.BookSources
import com.qzkt.timetable.data.book.BookStore
import com.qzkt.timetable.data.book.CategorizedBookSource
import com.qzkt.timetable.data.book.NlcCatalog
import com.qzkt.timetable.data.book.NlcRecord
import com.qzkt.timetable.data.book.NlcSearchPage
import com.qzkt.timetable.data.book.OnlineBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 书城分类浏览的状态。 */
data class StoreCategoryState(
    val categoryId: String = "",
    val page: Int = 0,
    val books: List<OnlineBook> = emptyList(),
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** 翻页时 true：新页追加到已有列表后面。 */
    val append: Boolean = false,
)

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

    /** 在线书源 key（设置页可切换）。 */
    private val _sourceKey = MutableStateFlow("kunnu8")
    val sourceKey: StateFlow<String> = _sourceKey

    val onlineSource: CategorizedBookSource
        get() = BookSources.byKey(_sourceKey.value) as? CategorizedBookSource
            ?: BookSources.all.filterIsInstance<CategorizedBookSource>().first()

    /** 全部内置在线书源（设置页列出来供切换）。 */
    val availableSources: List<BookSource> get() = BookSources.all

    /** 分类页签（显示名），书城分类栏用。 */
    val categories: List<Pair<String, String>> get() = onlineSource.categories

    /** 书城分类浏览状态。 */
    private val _category = MutableStateFlow(StoreCategoryState())
    val category: StateFlow<StoreCategoryState> = _category

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

    /** 书城搜书（用当前选中的在线书源）。 */
    fun search(keyword: String) {
        val source: BookSource = BookSources.byKey(_sourceKey.value)
        val query = keyword.trim()
        if (query.isEmpty() || _searching.value) return
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            _category.value = StoreCategoryState()   // 搜索时退出分类浏览，让结果显示出来
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

    fun setOnlineSource(key: String) {
        if (key == _sourceKey.value) return
        viewModelScope.launch {
            store.selectSource(key)
            _sourceKey.value = key
            _category.value = StoreCategoryState()   // 换源后分类从头来
        }
    }

    /** 打开一个分类（加载第 1 页），并清掉搜索结果让分类列表显示出来。 */
    fun openCategory(categoryId: String) {
        if (_category.value.categoryId == categoryId && _category.value.books.isNotEmpty()) return
        _searchResults.value = null
        _searchError.value = null
        loadCategoryPage(categoryId, 1)
    }

    /** 分类翻页（追加）。 */
    fun categoryNextPage() {
        val state = _category.value
        if (state.loading || !state.hasNext) return
        loadCategoryPage(state.categoryId, state.page + 1)
    }

    private fun loadCategoryPage(categoryId: String, page: Int) {
        viewModelScope.launch {
            _category.value = _category.value.copy(
                categoryId = categoryId,
                loading = true,
                error = null,
                append = page > 1,
            )
            runCatching { onlineSource.categoryBooks(categoryId, page) }
                .onSuccess { result ->
                    _category.value = _category.value.copy(
                        loading = false,
                        page = page,
                        hasNext = result.hasNext,
                        books = if (page > 1) _category.value.books + result.books else result.books,
                    )
                }
                .onFailure { e ->
                    _category.value = _category.value.copy(loading = false, error = e.message ?: "加载失败")
                }
        }
    }

    /** 设置页「图书数据管理」：清空书架。 */
    fun clearAllBooks() {
        viewModelScope.launch { store.clearAllBooks() }
    }
}

class BookViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BookViewModel(container.bookStore) as T
}
