package com.qzkt.timetable.ui.player.link

/**
 * 解析结果：一条能直接交给播放器的地址，外加它需要的请求头和作品信息。
 *
 * [headers] 是「用户填的 + 平台要求的」合并结果，调用方直接塞给
 * [com.qzkt.timetable.ui.player.StreamHeaders] 就行。
 * 作品信息（播放量 / 简介 / 发布时间 / 作者 / 封面）解析器能拿到多少给多少，
 * 拿不到的字段是 null —— 界面上跳过不显示。
 */
data class ResolvedMedia(
    val url: String,
    val title: String?,
    val headers: Map<String, String>,
    /** 来源平台；普通流地址（直链）不走平台解析，为 null。 */
    val platform: MediaPlatform?,
    /** 需要提醒用户、但不算失败的情况（例如视频被切成多段，只取了第一段）。 */
    val warning: String? = null,
    /** 播放量（次数）；平台不公开时为 null。 */
    val playCount: Long? = null,
    /** 简介 / 作品描述。 */
    val description: String? = null,
    /** 发布时间（epoch 秒）。 */
    val publishTime: Long? = null,
    /** 作者 / UP主。 */
    val author: String? = null,
    /** 封面图地址。 */
    val cover: String? = null,
)

/**
 * 解析失败。message 会原样显示给用户，所以写成人话，并带上接口报的原因。
 */
class LinkResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
