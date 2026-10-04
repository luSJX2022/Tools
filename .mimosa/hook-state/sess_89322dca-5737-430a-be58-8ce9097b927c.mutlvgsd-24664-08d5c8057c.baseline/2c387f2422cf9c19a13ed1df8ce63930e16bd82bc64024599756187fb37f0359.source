package com.qzkt.timetable.ui.player.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 下载解析出来的视频。
 *
 * 这里只管「把地址写进一个输出流」，落盘交给调用方（Android 那边要区分
 * MediaStore 和应用私有目录，见 [com.qzkt.timetable.ui.player.VideoDownloadStore]）——
 * 这样这一半是纯 HTTP，能直接用假服务器测。
 *
 * 请求头必须和播放时**完全一样**：B站 / 抖音的 CDN 都校验 Referer / UA，
 * 少了就是 403，被截断的文件还特别难查。
 */
class MediaDownloader(private val client: OkHttpClient = defaultLinkClient()) {

    /**
     * @param onProgress 已写字节数 / 总字节数（服务端没给 Content-Length 时为 null）。
     * @return 实际写入的字节数。
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        sink: OutputStream,
        onProgress: (written: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw LinkResolveException("下载失败：服务器返回 HTTP " + response.code)
            }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            // 进度回调太密会一直刷界面，按 1% 或 256KB 节流一次
            var written = 0L
            var lastReport = 0L
            val step = total?.let { (it / 100).coerceAtLeast(1L) } ?: (256L * 1024)
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    // 页面退出时协程被取消，这里主动中断，别一直下到磁盘满
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    if (written - lastReport >= step) {
                        lastReport = written
                        onProgress(written, total)
                    }
                }
            }
            sink.flush()
            onProgress(written, total)
            written
        }
    }
}

/** 文件名的扩展名白名单：认得出才用，认不出按 mp4 处理。 */
private val KNOWN_EXTENSIONS = listOf("mp4", "mkv", "flv", "webm", "mov", "m4v", "ts", "m4a", "mp3", "aac")

private val ILLEGAL_NAME_CHARS = Regex("""[\\/:*?"<>|\r\n\t]""")

/** HLS / DASH 是播放列表不是媒体文件，直接下回来放不了，得合并分片。 */
fun canDownloadDirectly(url: String): Boolean = rawExtensionOf(url) !in setOf("m3u8", "mpd")

/** 地址里认得出的**媒体**扩展名；`.m3u8` 这类播放列表故意不算。 */
fun extensionOf(url: String): String? = rawExtensionOf(url)?.takeIf { it in KNOWN_EXTENSIONS }

private fun rawExtensionOf(url: String): String? {
    val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
    val dot = path.lastIndexOf('.')
    if (dot <= 0 || dot == path.length - 1) return null
    return path.substring(dot + 1).lowercase()
}

/**
 * 用标题当文件名：去掉不能做文件名的字符，太长就截断，认不出扩展名时按 .mp4。
 *
 * 标题本身常常已经带扩展名（直链的标题就是地址最后一段的 `movie.mp4`），
 * 先把这个后缀去掉，免得存成 `movie.mp4.mp4`。
 */
fun downloadableFileName(title: String?, url: String, fallback: String = "video"): String {
    val ext = extensionOf(url) ?: "mp4"
    val cleaned = (title ?: "").replace(ILLEGAL_NAME_CHARS, "_").trim().trimEnd('.', ' ')
    val base = cleaned.removeSuffix("." + ext).take(60).ifBlank { fallback }
    return base + "." + ext
}

/** 给 MediaStore 用的 MIME；认不出就当视频。 */
fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "flv" -> "video/x-flv"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    else -> "video/mp4"
}

/** 「12.3 MB」这种给人看的大小。 */
fun readableSize(bytes: Long): String = when {
    // 固定用 Locale.US：逗号小数点的地区会把「1.5 MB」写成「1,5 MB」
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
