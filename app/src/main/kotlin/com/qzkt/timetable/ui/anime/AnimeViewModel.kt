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
import com.qzkt.timetable.data.anime.AnimePage
import com.qzkt.timetable.data.anime.AnimeStore
import com.qzkt.timetable.data.anime.AnimeSource
import com.qzkt.timetable.data.anime.CustomAnimeSource
import com.qzkt.timetable.data.anime.MacCmsSource
import com.qzkt.timetable.data.anime.VideoSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

/**
 * 首页「推荐」和「排行」两个页签的分页状态。
 * 跟筛选页签的 [AnimeListState] 分开：三个页签各自翻页互不干扰。
 */
data class AnimeFeedState(
    /** 已加载到第几页；0 = 还没加载。 */
    val page: Int = 0,
    val totalPages: Int = 1,
    val items: List<AnimeItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = page in 1 until totalPages
}

class AnimeViewModel(private val store: AnimeStore) : ViewModel() {

    private val _sourceKey = MutableStateFlow(VideoSources.all.first().key)

    /** 当前选中的影视源 key（源管理页里可以切换）。 */
    val sourceKey: StateFlow<String> = _sourceKey

    private val _customSources = MutableStateFlow<List<CustomAnimeSource>>(emptyList())
    private val _disabledKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 可用源列表 = 内置源（去掉停用的）+ 自定义源（去掉停用的）。
     * 影视页的分类页签、源管理页都读这个。
     */
    val sources: StateFlow<List<AnimeSource>> = combine(_customSources, _disabledKeys) { customs, disabled ->
        VideoSources.all.filter { it.key !in disabled } +
            customs.filter { it.key !in disabled }.map { custom ->
                MacCmsSource(
                    key = custom.key,
                    name = custom.name,
                    baseUrl = custom.baseUrl,
                    // 自定义源不知道分类 id，给一个「全部」：苹果 CMS 不带 t 就是全站最新
                    categories = listOf("全部" to ""),
                )
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoSources.all.toList())

    val source: AnimeSource
        get() = sources.value.firstOrNull { it.key == _sourceKey.value }
            ?: sources.value.firstOrNull()
            ?: VideoSources.byKey(_sourceKey.value)

    /** 分类页签（显示名），跟随当前源变化。 */
    val categories: List<Pair<String, String>> get() = source.categories

    /** 自定义源（源管理页展示 / 删除用），含被停用的。 */
    val customSources: StateFlow<List<CustomAnimeSource>> = _customSources

    /** 被停用的源 key（源管理页开关用）。 */
    val disabledSources: StateFlow<Set<String>> = _disabledKeys

    private val _list = MutableStateFlow(AnimeListState())
    val list: StateFlow<AnimeListState> = _list

    /** 首页「推荐」feed：当前源第一个分类的分页列表（banner 也从这里取前几部）。 */
    private val _home = MutableStateFlow(AnimeFeedState())
    val home: StateFlow<AnimeFeedState> = _home
    private var homeCategoryId: String = ""

    /** 「排行」feed：各源没有排行接口，用「不限分类的最新更新」代替。 */
    private val _latest = MutableStateFlow(AnimeFeedState())
    val latest: StateFlow<AnimeFeedState> = _latest

    private val _detail = MutableStateFlow(AnimeDetailState())
    val detail: StateFlow<AnimeDetailState> = _detail

    val favorites: StateFlow<List<AnimeFavorite>> = store.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<Map<String, AnimeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AnimeProgress>> = _progress

    /** 搜索历史（最新的在前），影视页搜索框为空时展示。 */
    val searchHistory: StateFlow<List<String>> = store.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                    homeCategoryId = source.categories.first().second
                    _home.value = AnimeFeedState()
                    _latest.value = AnimeFeedState()
                    loadMoreHome()
                }
            }
        }
        viewModelScope.launch { store.progress.collect { _progress.value = it } }
        viewModelScope.launch { store.customSources.collect { _customSources.value = it } }
        viewModelScope.launch { store.disabledSources.collect { _disabledKeys.value = it } }
        // 当前源被停用 / 删掉时，回退到第一个可用源
        viewModelScope.launch {
            sources.collect { list ->
                if (list.isNotEmpty() && list.none { it.key == _sourceKey.value }) {
                    store.selectSource(list.first().key)
                }
            }
        }
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
        viewModelScope.launch { store.addSearchHistory(query) }
        _list.value = AnimeListState(mode = AnimeListState.Mode.SEARCH, query = query)
        loadPage(reset = true)
    }

    fun clearSearchHistory() {
        viewModelScope.launch { store.clearSearchHistory() }
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

    /** 源管理页切换影视源：写本地，collect 里会自动重置列表并重新加载。 */
    fun selectSource(key: String) {
        if (key == _sourceKey.value) return
        viewModelScope.launch { store.selectSource(key) }
    }

    /** 启用 / 停用某个源（停用的源不在影视页出现）。 */
    fun setSourceEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch { store.setSourceEnabled(key, enabled) }
    }

    /** 添加自定义采集站。返回错误信息，null = 成功。 */
    fun addCustomSource(name: String, baseUrl: String): String? {
        val cleanName = name.trim()
        val cleanUrl = normalizeCustomSourceUrl(baseUrl) ?: return "请输入有效的 API 地址"
        if (cleanName.isEmpty()) return "请输入源名称"
        viewModelScope.launch { store.addCustomSource(CustomAnimeSource(cleanName, cleanUrl)) }
        return null
    }

    fun removeCustomSource(baseUrl: String) {
        viewModelScope.launch { store.removeCustomSource(baseUrl) }
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

    /** 首页「推荐」触底翻页。 */
    fun loadMoreHome() {
        loadFeed(_home) { page -> source.list(homeCategoryId, page) }
    }

    /** 「排行」页签第一次打开时才加载，省一次启动请求。 */
    fun ensureLatestLoaded() {
        val s = _latest.value
        if (s.items.isEmpty() && !s.loading && s.error == null) loadMoreLatest()
    }

    fun loadMoreLatest() {
        loadFeed(_latest) { page -> fetchLatest(page) }
    }

    /**
     * 「排行」= 不限分类的最新更新。苹果 CMS 不带 `t` 就是全站最新；
     * Internet Archive 这类不认空分类的源退回它的第一个分类。
     */
    private suspend fun fetchLatest(page: Int): AnimePage =
        runCatching { source.list("", page) }
            .getOrElse { source.list(source.categories.first().second, page) }

    /** 通用分页：首页 / 排行两个 feed 共用一套触底翻页逻辑。 */
    private fun loadFeed(
        feed: MutableStateFlow<AnimeFeedState>,
        fetch: suspend (Int) -> AnimePage,
    ) {
        val state = feed.value
        if (state.loading || (state.page > 0 && !state.hasMore)) return
        val targetPage = if (state.page == 0) 1 else state.page + 1
        feed.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { fetch(targetPage) }
                .onSuccess { page ->
                    feed.update {
                        it.copy(
                            page = page.page,
                            totalPages = page.totalPages,
                            items = if (targetPage == 1) page.items else it.items + page.items,
                            loading = false,
                        )
                    }
                }
                .onFailure { e -> feed.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
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
            pic = detail.pic,
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

/**
 * 规范化自定义源的 API 地址：补协议、去查询参数和尾斜杠；
 * 用户直接粘贴完整采集地址（…/api.php/provide/vod）时剥掉后缀，只留站点根地址。
 * 返回 null = 输入没法用。
 */
internal fun normalizeCustomSourceUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "https://$s"
    }
    s = s.substringBefore('?').trimEnd('/')
    val marker = s.indexOf("/api.php", startIndex = 9)
    if (marker > 0) s = s.substring(0, marker)
    val host = s.removePrefix("https://").removePrefix("http://").substringBefore('/')
    if (host.isEmpty() || !host.contains('.')) return null
    return s
}
