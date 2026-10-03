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
    /** 选中的在线书源 key（对应 BookSources.byKey）。默认鲲弩（国内手机可达）。 */
    val sourceKey: String = "kunnu8",
    val books: List<Book> = emptyList(),
    /** 阅读页正文字号（sp），全库共用。 */
    val readerFontSize: Int = 18,
    /** 阅读页夜间模式。 */
    val readerNight: Boolean = false,
)

/** 书架上的一本书：kind = local（txt 文件）或 online（在线书源）。 */
@Serializable
data class Book(
    val id: String,
    val name: String,
    /** local：content:// 地址；online：书籍落地页路径。 */
    val uri: String,
    val addedAt: Long,
    val kind: String = "local",
    /** online 用：书源 key。 */
    val sourceKey: String = "",
    /** online 用：书籍落地页路径。 */
    val bookUrl: String = "",
    val author: String = "",
    val cover: String = "",
    /** 读到的位置：local 是段落下标，online 是章节下标（0 起）。 */
    val lastIndex: Int = 0,
    /** local：段落总数；online：章节总数。 */
    val lastParagraphTotal: Int = 0,
)

/** 本地 TXT 书架 + 在线书源 + 阅读设置。 */
class BookStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val booksKey = stringPreferencesKey("books_json")
    private val fontSizeKey = intPreferencesKey("reader_font_size")
    private val nightKey = booleanPreferencesKey("reader_night")
    private val sourceKeyKey = stringPreferencesKey("book_source_key")

    private val data: Flow<BookStoreData> = context.bookDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[booksKey]?.let { raw ->
                runCatching { json.decodeFromString(BookStoreData.serializer(), raw) }.getOrNull()
            } ?: BookStoreData()
        }

    /** 书架，最新导入的在前。 */
    val books: Flow<List<Book>> = data.map { it.books.sortedByDescending { b -> b.addedAt } }

    /** 选中的在线书源 key。 */
    val selectedSource: Flow<String> = data.map { it.sourceKey }

    /** 切换在线书源（设置页用）。 */
    suspend fun selectSource(sourceKey: String) {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(sourceKey = sourceKey),
            )
        }
    }

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

    /** 导入一本在线书（书城点开时）；同一落地页重复添加只更新信息并保留进度。返回书籍 id。 */
    suspend fun addOnlineBook(book: OnlineBook): String {
        val existing = data.firstOrNull() ?: BookStoreData()
        val id = existing.books
            .firstOrNull { it.kind == "online" && it.bookUrl == book.bookUrl }?.id
            ?: UUID.randomUUID().toString()
        val previous = existing.books.firstOrNull { it.id == id }
        val updated = existing.copy(
            books = existing.books.filterNot { it.id == id } + Book(
                id = id,
                name = book.name,
                uri = book.bookUrl,
                addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                kind = "online",
                sourceKey = book.sourceKey,
                bookUrl = book.bookUrl,
                author = book.author ?: "",
                cover = book.cover ?: "",
                lastIndex = previous?.lastIndex ?: 0,
                lastParagraphTotal = previous?.lastParagraphTotal ?: 0,
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

    /** 清空书架（保留阅读偏好与书源选择；txt 文件本体不受影响）。 */
    suspend fun clearAllBooks() {
        context.bookDataStore.edit { prefs ->
            val existing = decode(prefs[booksKey])
            prefs[booksKey] = json.encodeToString(
                BookStoreData.serializer(),
                existing.copy(books = emptyList()),
            )
        }
    }

    private fun decode(raw: String?): BookStoreData =
        raw?.let { runCatching { json.decodeFromString(BookStoreData.serializer(), it) }.getOrNull() }
            ?: BookStoreData()
}
