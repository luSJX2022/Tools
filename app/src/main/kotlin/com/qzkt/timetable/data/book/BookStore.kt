package com.qzkt.timetable.data.book

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.bookDataStore: DataStore<Preferences> by preferencesDataStore(name = "qzkt_books")

@Serializable
private data class BookStoreData(
    val books: List<Book> = emptyList(),
    /** 阅读页正文字号（sp），全库共用。 */
    val readerFontSize: Int = 18,
    /** 阅读页夜间模式。 */
    val readerNight: Boolean = false,
)

/** 书架上的一本书（txt 文件本体留在原处，这里只记地址和进度）。 */
@Serializable
data class Book(
    val id: String,
    val name: String,
    /** content:// 地址，导入时已拿持久化读权限。 */
    val uri: String,
    val addedAt: Long,
    /** 读到的段落下标（0 起），配合 [lastParagraphTotal] 算百分比。 */
    val lastIndex: Int = 0,
    val lastParagraphTotal: Int = 0,
)

/** 本地 TXT 书架 + 阅读设置。在线书源以后再加。 */
class BookStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val booksKey = stringPreferencesKey("books_json")
    private val fontSizeKey = intPreferencesKey("reader_font_size")
    private val nightKey = booleanPreferencesKey("reader_night")

    private val data: Flow<BookStoreData> = context.bookDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[booksKey]?.let { raw ->
                runCatching { json.decodeFromString(BookStoreData.serializer(), raw) }.getOrNull()
            } ?: BookStoreData()
        }

    /** 书架，最新导入的在前。 */
    val books: Flow<List<Book>> = data.map { it.books.sortedByDescending { b -> b.addedAt } }

    val readerFontSize: Flow<Int> = data.map { it.readerFontSize }

    val readerNight: Flow<Boolean> = data.map { it.readerNight }

    /** 导入一本书；同一地址重复导入只更新名字。返回书籍 id。 */
    suspend fun addBook(name: String, uri: String): String {
        val existing = data.firstOrNull() ?: BookStoreData()
        val id = existing.books.firstOrNull { it.uri == uri }?.id ?: UUID.randomUUID().toString()
        val updated = existing.copy(
            books = existing.books
                .filterNot { it.uri == uri } + Book(
                id = id,
                name = name,
                uri = uri,
                addedAt = System.currentTimeMillis(),
                lastIndex = existing.books.firstOrNull { it.uri == uri }?.lastIndex ?: 0,
                lastParagraphTotal = existing.books.firstOrNull { it.uri == uri }?.lastParagraphTotal ?: 0,
            ),
        )
        context.bookDataStore.edit { prefs -> prefs[booksKey] = json.encodeToString(BookStoreData.serializer(), updated) }
        return id
    }

    suspend fun removeBook(id: String) {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(books = existing.books.filterNot { it.id == id }),
            )
        }
    }

    /** 记录阅读进度（段落下标）。 */
    suspend fun saveProgress(id: String, lastIndex: Int, paragraphTotal: Int) {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(
                    books = existing.books.map { book ->
                        if (book.id == id) {
                            book.copy(lastIndex = lastIndex, lastParagraphTotal = paragraphTotal)
                        } else {
                            book
                        }
                    },
                ),
            )
        }
    }

    suspend fun setReaderFontSize(size: Int) {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(readerFontSize = size.coerceIn(14, 30)),
            )
        }
    }

    suspend fun setReaderNight(night: Boolean) {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(readerNight = night),
            )
        }
    }

    private fun decode(raw: String?): BookStoreData =
        raw?.let { runCatching { json.decodeFromString(BookStoreData.serializer(), it) }.getOrNull() }
            ?: BookStoreData()
}
