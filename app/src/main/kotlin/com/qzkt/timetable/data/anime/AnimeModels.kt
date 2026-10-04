package com.qzkt.timetable.data.anime

import kotlinx.serialization.Serializable

/** 列表 / 搜索结果里的一部番。 */
@Serializable
data class AnimeItem(
    /** 所属源的唯一名（bfzy / liangzi …），收藏和进度都挂在「源 + id」上。 */
    val source: String,
    val id: String,
    val name: String,
    val pic: String = "",
    /** 备注，一般是「更新至第 N 集」「已完结」这类。 */
    val remark: String = "",
)

/** 一集可播放的内容。 */
@Serializable
data class Episode(
    val label: String,
    val url: String,
)

/** 详情页完整数据（含剧集列表）。 */
data class AnimeDetail(
    val source: String,
    val id: String,
    val name: String,
    val pic: String = "",
    val remark: String = "",
    val year: String = "",
    val area: String = "",
    val genre: String = "",
    /** 简介（已去掉 HTML 标签）。 */
    val content: String = "",
    val episodes: List<Episode> = emptyList(),
)

/** 本地收藏的一条追番。 */
@Serializable
data class AnimeFavorite(
    val source: String,
    val id: String,
    val name: String,
    val pic: String = "",
    val remark: String = "",
    val addedAt: Long,
)

/** 观看进度：看到第几集。 */
@Serializable
data class AnimeProgress(
    val source: String,
    val id: String,
    /** 0 起的集数下标（剧集列表里的位置）。 */
    val episodeIndex: Int,
    val episodeLabel: String,
    val episodeCount: Int = 0,
    val updatedAt: Long,
)

/** 交给播放器页的播放请求。带上整部番的上下文，播放器页里才能直接选集换集。 */
@Serializable
data class AnimePlayRequest(
    /** 当前要播的那集地址。 */
    val url: String,
    /** 显示在播放器标题上的，形如「番名 · 第 N 集」。 */
    val title: String,
    val source: String = "",
    val id: String = "",
    val name: String = "",
    val episodes: List<Episode> = emptyList(),
    /** 当前集的下标（0 起）。 */
    val currentIndex: Int = 0,
    /** 作品简介（B站 desc / 抖音文案）；链接解析时带过来显示在播放页底部。 */
    val description: String? = null,
)
