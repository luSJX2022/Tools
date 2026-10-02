package com.qzkt.timetable.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookSource
import com.qzkt.timetable.data.book.BookSources
import com.qzkt.timetable.data.book.BookStore
import com.qzkt.timetable.data.book.OnlineBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
