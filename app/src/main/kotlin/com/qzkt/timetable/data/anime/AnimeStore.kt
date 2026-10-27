package com.qzkt.timetable.data.anime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.animeDataStore: DataStore<Preferences> by preferencesDataStore(name = "qzkt_anime")

@Serializable
private data class AnimeStoreData(
    /** 选中的影视源 key，对应 [VideoSources.all] 或 [CustomAnimeSource.key]。 */
    val sourceKey: String = "bfzy",
    val favorites: List<AnimeFavorite> = emptyList(),
    /** key 是「source:id」。 */
    val progress: Map<String, AnimeProgress> = emptyMap(),
    /** 搜索历史，最新的在前，去重，最多 [MAX_SEARCH_HISTORY] 条。 */
    val searchHistory: List<String> = emptyList(),
    /** 用户自己添加的苹果 CMS 采集站。 */
    val customSources: List<CustomAnimeSource> = emptyList(),
    /** 被停用的源 key（内置 + 自定义通用），停用后不在影视页出现。 */
    val disabledSources: List<String> = emptyList(),
)

/** 用户自定义的苹果 CMS10 采集站（源管理页里手动添加）。 */
@Serializable
data class CustomAnimeSource(
    val name: String,
    val baseUrl: String,
) {
    /** 稳定 key：同一地址重复添加会互相覆盖。 */
    val key: String get() = "custom:$baseUrl"
}

/** 追番收藏和观看进度的本地存储（不联网同步）。 */
class AnimeStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("anime_json")

    private val data: Flow<AnimeStoreData> = context.animeDataStore.data
        // 文件坏了退回空数据，别让应用崩
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decode(prefs[key]) }

    /** 收藏列表，最新的在前。 */
    val favorites: Flow<List<AnimeFavorite>> = data.map { it.favorites.sortedByDescending { f -> f.addedAt } }

    /** 选中的影视源 key。 */
    val selectedSource: Flow<String> = data.map { it.sourceKey }

    /** 切换影视源（设置页用）。 */
    suspend fun selectSource(sourceKey: String) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(sourceKey = sourceKey),
            )
        }
    }

    /** 用户自定义的采集站列表。 */
    val customSources: Flow<List<CustomAnimeSource>> = data.map { it.customSources }

    /** 被停用的源 key 集合。 */
    val disabledSources: Flow<Set<String>> = data.map { it.disabledSources.toSet() }

    /** 添加自定义采集站；同地址会覆盖旧的名字。 */
    suspend fun addCustomSource(source: CustomAnimeSource) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            val rest = existing.customSources.filterNot { it.baseUrl == source.baseUrl }
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(customSources = rest + source),
            )
        }
    }

    suspend fun removeCustomSource(baseUrl: String) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(
                    customSources = existing.customSources.filterNot { it.baseUrl == baseUrl },
                    // 顺手清掉它的停用标记
                    disabledSources = existing.disabledSources.filterNot { it == "custom:$baseUrl" },
                ),
            )
        }
    }

    /** 启用 / 停用某个源。 */
    suspend fun setSourceEnabled(sourceKey: String, enabled: Boolean) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            val disabled = if (enabled) {
                existing.disabledSources.filterNot { it == sourceKey }
            } else {
                (existing.disabledSources + sourceKey).distinct()
            }
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(disabledSources = disabled),
            )
        }
    }

    /** key 为「source:id」的进度表。 */
    val progress: Flow<Map<String, AnimeProgress>> = data.map { it.progress }

    /** 搜索历史，最新的在前。 */
    val searchHistory: Flow<List<String>> = data.map { it.searchHistory }

    /** 记一条搜索历史：去重置顶，超出上限裁掉最旧的那条。 */
    suspend fun addSearchHistory(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(
                    searchHistory = (listOf(keyword) + existing.searchHistory.filterNot { it == keyword })
                        .take(MAX_SEARCH_HISTORY),
                ),
            )
        }
    }

    suspend fun clearSearchHistory() {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(searchHistory = emptyList()),
            )
        }
    }

    suspend fun toggleFavorite(item: AnimeFavorite): Boolean {
        var added = false
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            val isFav = existing.favorites.any { it.source == item.source && it.id == item.id }
            added = !isFav
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(
                    favorites = if (isFav) {
                        existing.favorites.filterNot { it.source == item.source && it.id == item.id }
                    } else {
                        existing.favorites + item
                    },
                ),
            )
        }
        return added
    }

    /** 清空全部收藏（进度保留不动，设置页的存储管理用）。 */
    suspend fun clearFavorites() {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(favorites = emptyList()),
            )
        }
    }

    suspend fun clearPluginData() {
        context.animeDataStore.edit { it.remove(key) }
    }

    suspend fun saveProgress(progress: AnimeProgress) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(progress = existing.progress + ("${progress.source}:${progress.id}" to progress)),
            )
        }
    }

    /** 备份恢复：整体替换收藏、进度、影视源和搜索历史。 */
    suspend fun restore(
        sourceKey: String?,
        favorites: List<AnimeFavorite>,
        progress: Map<String, AnimeProgress>,
        searchHistory: List<String>,
    ) {
        context.animeDataStore.edit { prefs ->
            val existing = decode(prefs[key])
            prefs[key] = json.encodeToString(
                AnimeStoreData.serializer(),
                existing.copy(
                    sourceKey = sourceKey ?: existing.sourceKey,
                    favorites = favorites,
                    progress = progress,
                    searchHistory = searchHistory.take(MAX_SEARCH_HISTORY),
                ),
            )
        }
    }

    private fun decode(raw: String?): AnimeStoreData =
        raw?.let { runCatching { json.decodeFromString(AnimeStoreData.serializer(), it) }.getOrNull() }
            ?: AnimeStoreData()

    private companion object {
        /** 搜索历史最多保留的条数。 */
        const val MAX_SEARCH_HISTORY = 20
    }
}
