package com.qzkt.timetable.data.backup

import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.SettingsStore
import com.qzkt.timetable.data.anime.AnimeFavorite
import com.qzkt.timetable.data.anime.AnimeProgress
import com.qzkt.timetable.data.anime.AnimeStore
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份文件的全部内容：设置 + 书架 + 追番三块。
 *
 * 字段全部可空/带默认值 —— 老备份缺字段、导入时缺哪块就跳过哪块，
 * 不会因为版本演进而解不开。
 *
 * 注意：settings 里含教务账号密码，备份文件请别外传。
 */
@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val settings: AppSettings? = null,
    val bookSourceKey: String? = null,
    val readerFontSize: Int? = null,
    val readerNight: Boolean? = null,
    val books: List<Book> = emptyList(),
    val animeSourceKey: String? = null,
    val animeFavorites: List<AnimeFavorite> = emptyList(),
    val animeProgress: Map<String, AnimeProgress> = emptyMap(),
    val animeSearchHistory: List<String> = emptyList(),
)

/** 把设置、书架、追番数据打包成一份 JSON： 导出到用户选的文件 / 从文件恢复。 */
class BackupManager(
    private val settingsStore: SettingsStore,
    private val bookStore: BookStore,
    private val animeStore: AnimeStore,
) {

    /** 当前全部数据的快照。 */
    suspend fun export(): BackupData = BackupData(
        exportedAt = System.currentTimeMillis(),
        settings = settingsStore.settings.first(),
        bookSourceKey = bookStore.selectedSource.first(),
        readerFontSize = bookStore.readerFontSize.first(),
        readerNight = bookStore.readerNight.first(),
        books = bookStore.books.first(),
        animeSourceKey = animeStore.selectedSource.first(),
        animeFavorites = animeStore.favorites.first(),
        animeProgress = animeStore.progress.first(),
        animeSearchHistory = animeStore.searchHistory.first(),
    )

    /** 用备份覆盖本地数据（整体替换，不做合并）。 */
    suspend fun restore(data: BackupData) {
        val settings = data.settings
        if (settings != null) settingsStore.update { settings }
        bookStore.restore(data.bookSourceKey, data.readerFontSize, data.readerNight, data.books)
        animeStore.restore(data.animeSourceKey, data.animeFavorites, data.animeProgress, data.animeSearchHistory)
    }

    companion object {
        /** 导出/导入共用的 JSON 配置：容忍未知字段（版本前向兼容）。 */
        val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
