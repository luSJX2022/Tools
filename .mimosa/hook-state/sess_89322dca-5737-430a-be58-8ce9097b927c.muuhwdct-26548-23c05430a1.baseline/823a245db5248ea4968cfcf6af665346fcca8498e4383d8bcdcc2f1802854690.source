package com.qzkt.timetable.ui.player

import androidx.media3.datasource.DataSpec

/**
 * 播放流媒体时附带的 HTTP 请求头。
 *
 * 不少站的视频流要带 `Referer` / `User-Agent` 才给数据（防盗链），
 * 少了就表现为 403，或者连上立刻断。
 *
 * 请求头只能从数据源那一层注入，而播放器在 [PlaybackService] 里，
 * 拿不到页面上的状态，所以这里放一份当前值由双方共享。
 * 一次只播一路流，所以不用按媒体项分开存。
 */
object StreamHeaders {

    @Volatile
    var current: Map<String, String> = emptyMap()
        private set

    fun set(headers: Map<String, String>) {
        current = headers
    }

    fun clear() = set(emptyMap())

    /** 给每个网络请求挂上当前请求头，空的时候原样放行。 */
    fun applyTo(dataSpec: DataSpec): DataSpec =
        if (current.isEmpty()) dataSpec else dataSpec.withRequestHeaders(current)
}

/**
 * 解析页面里手填的请求头，一行一个 `名称: 值`。
 *
 * 空行、`#` 开头的注释行、没有冒号的行都跳过；名称不区分大小写地归一成
 * 标准写法（`referer` 和 `Referer` 是同一个头，重复时后面的覆盖前面的）。
 */
fun parseHeaderBlock(text: String): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val colon = line.indexOf(':')
        if (colon <= 0) return@forEach
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (name.isEmpty() || value.isEmpty()) return@forEach
        headers[canonicalHeaderName(name)] = value
    }
    return headers
}

/**
 * 已知的头按标准大小写写回，HTTP/2 之外的头名不区分大小写，
 * 但 `Referer` 写成 `referer` 有些服务端会认不出。
 */
private fun canonicalHeaderName(name: String): String = when (name.lowercase()) {
    "referer" -> "Referer"
    "referrer" -> "Referer"
    "user-agent" -> "User-Agent"
    "origin" -> "Origin"
    "cookie" -> "Cookie"
    "authorization" -> "Authorization"
    "accept" -> "Accept"
    "accept-language" -> "Accept-Language"
    else -> name
}
