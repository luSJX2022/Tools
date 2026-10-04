package com.qzkt.timetable.ui.anime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qzkt.timetable.AppContainer
import com.qzkt.timetable.data.anime.AnimeFavorite
import com.qzkt.timetable.data.anime.AnimePlayRequest
import com.qzkt.timetable.data.anime.AnimeProgress
import com.qzkt.timetable.data.anime.AnimeDetail
import com.qzkt.timetable.data.anime.AnimeItem
import com.qzkt.timetable.data.anime.AnimeStore
import com.qzkt.timetable.data.anime.MacCmsSource
import com.qzkt.timetable.data.anime.VideoSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 影视列表页的状态：分类浏览 / 搜索 / 收藏三种模式共用一套分页列表。 */
data class AnimeListState(
    val mode: Mode = Mode.CATEGORY,
    /** 当前源的分类 id（可逗号分隔多个）。 */
    val categoryId: String = "",
    val query: String = "",
    /** 已加载到第几页；0 = 还没加载。 */
    val page: Int = 0,
    val totalPages: Int = 1,
    val items: List<AnimeItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    enum class Mode { CATEGORY, SEARCH, FAVORITES }

    val hasMore: Boolean get() = page in 1 until totalPages
}

/** 详情页状态。 */
data class AnimeDetailState(
    val loading: Boolean = false,
    val detail: AnimeDetail? = null,
    val error: String? = null,
)

class AnimeViewModel(private val store: AnimeStore) : ViewModel() {

    private val _sourceKey = MutableStateFlow(VideoSources.all.first().key)

    /** 当前选中的影视源 key（设置页里可以切换）。 */
    val sourceKey: StateFlow<String> = _sourceKey

    val source: MacCmsSource get() = VideoSources.byKey(_sourceKey.value)

    /** 分类页签（显示名），跟随当前源变化。 */
    val categories: List<Pair<String, String>> get() = source.categories

    val availableSources: List<MacCmsSource> get() = VideoSources.all

    private val _list = MutableStateFlow(AnimeListState())
    val list: StateFlow<AnimeListState> = _list

    private val _detail = MutableStateFlow(AnimeDetailState())
    val detail: StateFlow<AnimeDetailState> = _detail

    val favorites: StateFlow<List<AnimeFavorite>> = store.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<Map<String, AnimeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AnimeProgress>> = _progress

    /** 详情页点下的那一集，等 QzktApp 跳到播放器页时交给 [com.qzkt.timetable.ui.player.PlayerScreen] 消费。 */
    var pendingPlay: AnimePlayRequest? = null
        private set

    /** 最近一次番剧播放的上下文（含整部番的剧集）。放在 VM 里跨页面存活，
     *  退出播放器再从工具页进来，「选集」里还是这部番。 */
    private val _animeSession = MutableStateFlow<AnimePlayRequest?>(null)
    val animeSession: StateFlow<AnimePlayRequest?> = _animeSession

    init {
        viewModelScope.launch {
            var first = true
            store.selectedSource.collect { key ->
                val changed = _sourceKey.value != key
                _sourceKey.value = key
                if (first || changed) {
                    first = false
                    resetToDefaultCategory()
                }
            }
        }
        viewModelScope.launch { store.progress.collect { _progress.value = it } }
    }

    /** 打开当前源的第一个分类（启动 / 换源时走这里）。 */
    private fun resetToDefaultCategory() {
        _list.value = AnimeListState(
            mode = AnimeListState.Mode.CATEGORY,
            categoryId = source.categories.first().second,
        )
        loadPage(reset = true)
    }

    /** 打开一个分类（首屏也走这里）。 */
    fun selectCategory(categoryId: String) {
        if (_list.value.mode == AnimeListState.Mode.CATEGORY && _list.value.categoryId == categoryId &&
            _list.value.items.isNotEmpty()
        ) return
        _list.value = AnimeListState(mode = AnimeListState.Mode.CATEGORY, categoryId = categoryId)
        loadPage(reset = true)
    }

    fun search(keyword: String) {
        val query = keyword.trim()
        if (query.isEmpty()) return
        _list.value = AnimeListState(mode = AnimeListState.Mode.SEARCH, query = query)
        loadPage(reset = true)
    }

    /** 从搜索退回分类浏览。 */
    fun backToCategory() {
        val categoryId = _list.value.categoryId
        _list.value = AnimeListState(mode = AnimeListState.Mode.CATEGORY, categoryId = categoryId)
        loadPage(reset = true)
    }

    /** 收藏页签（数据走本地收藏，不请求网络）。 */
    fun showFavorites() {
        _list.value = AnimeListState(mode = AnimeListState.Mode.FAVORITES, categoryId = _list.value.categoryId)
    }

    /** 设置页切换影视源：写本地，collect 里会自动重置列表并重新加载。 */
    fun selectSource(key: String) {
        if (key == _sourceKey.value) return
        viewModelScope.launch { store.selectSource(key) }
    }

    /** 触底加载下一页。 */
    fun loadMore() {
        val state = _list.value
        if (state.loading || state.mode == AnimeListState.Mode.FAVORITES || !state.hasMore) return
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        if (_list.value.loading) return
        val state = _list.value
        val targetPage = if (reset) 1 else state.page + 1
        _list.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                when (state.mode) {
                    AnimeListState.Mode.SEARCH -> source.search(state.query, targetPage)
                    else -> source.list(state.categoryId, targetPage)
                }
            }.onSuccess { page ->
                _list.update {
                    it.copy(
                        page = page.page,
                        totalPages = page.totalPages,
                        items = if (reset) page.items else it.items + page.items,
                        loading = false,
                    )
                }
            }.onFailure { e ->
                _list.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun openDetail(id: String) {
        if (_detail.value.detail?.id == id || _detail.value.loading) return
        _detail.value = AnimeDetailState(loading = true)
        viewModelScope.launch {
            runCatching { source.detail(id) }
                .onSuccess { _detail.value = AnimeDetailState(detail = it) }
                .onFailure { e -> _detail.value = AnimeDetailState(error = e.message ?: e.toString()) }
        }
    }

    fun toggleFavorite(item: AnimeFavorite) {
        viewModelScope.launch { store.toggleFavorite(item) }
    }

    /** 设置页「存储管理」里清空追番。 */
    fun clearFavorites() {
        viewModelScope.launch { store.clearFavorites() }
    }

    /** 点下一集：记进度 + 准备播放请求（带整部番的剧集，播放器页里可以直接选集）。 */
    fun requestPlay(detail: AnimeDetail, episodeIndex: Int) {
        val episode = detail.episodes.getOrNull(episodeIndex) ?: return
        viewModelScope.launch {
            store.saveProgress(
                AnimeProgress(
                    source = detail.source,
                    id = detail.id,
                    episodeIndex = episodeIndex,
                    episodeLabel = episode.label,
                    episodeCount = detail.episodes.size,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        val request = AnimePlayRequest(
            url = episode.url,
            title = "${detail.name} · ${episode.label}",
            source = detail.source,
            id = detail.id,
            name = detail.name,
            episodes = detail.episodes,
            currentIndex = episodeIndex,
            description = detail.content.ifBlank { null },
        )
        _animeSession.value = request
        pendingPlay = request
    }

    /** 播放器页里换集：更新会话（选集列表的当前位置）并记进度。 */
    fun switchEpisode(request: AnimePlayRequest) {
        _animeSession.value = request
        val label = request.episodes.getOrNull(request.currentIndex)?.label ?: return
        viewModelScope.launch {
            store.saveProgress(
                AnimeProgress(
                    source = request.source,
                    id = request.id,
                    episodeIndex = request.currentIndex,
                    episodeLabel = label,
                    episodeCount = request.episodes.size,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 解析页：解析出来的地址交给播放器。没有剧集列表，清掉旧会话避免「选集」串台。 */
    fun playResolved(request: AnimePlayRequest) {
        _animeSession.value = null
        pendingPlay = request
    }

    /** 播放器页取走播放请求。 */
    fun consumePlayRequest(): AnimePlayRequest? = pendingPlay.also { pendingPlay = null }

    companion object {
        /** 进度表里的 key。 */
        fun progressKey(source: String, id: String) = "$source:$id"
    }
}

class AnimeViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AnimeViewModel(container.animeStore) as T
}
