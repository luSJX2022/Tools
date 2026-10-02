package com.qzkt.timetable.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookStore
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

    init {
        viewModelScope.launch {
            store.books.collect { _books.value = it }
        }
        viewModelScope.launch { store.readerFontSize.collect { _fontSize.value = it } }
        viewModelScope.launch { store.readerNight.collect { _night.value = it } }
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
