package com.qzkt.timetable.ui.player.link

/**
 * 解析结果：一条能直接交给播放器的地址，外加它需要的请求头。
 *
 * [headers] 是「用户填的 + 平台要求的」合并结果，调用方直接塞给
 * [com.qzkt.timetable.ui.player.StreamHeaders] 就行。
 */
data class ResolvedMedia(
    val url: String,
    val title: String?,
    val headers: Map<String, String>,
    val platform: MediaPlatform,
    /** 需要提醒用户、但不算失败的情况（例如视频被切成多段，只取了第一段）。 */
    val warning: String? = null,
)

/**
 * 解析失败。message 会原样显示给用户，所以写成人话，并带上接口报的原因。
 */
class LinkResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
