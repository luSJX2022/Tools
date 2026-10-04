package com.qzkt.timetable.ui.player.link

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 下载这一半是纯 HTTP（落盘在 Android 那边），所以能用假服务器直接测：
 * 字节有没有写全、进度对不对、请求头有没有带上。
 */
class MediaDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seenHeaders = CopyOnWriteArrayList<String>()

    private val payload = ByteArray(200_000) { index -> (index % 251).toByte() }

    /** true 时故意不发 Content-Length（分块传输），进度条只能是不确定态。 */
    private var chunked = false

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:" + server.address.port
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `把整段字节写进输出流并按总长度报进度`() = runBlocking {
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long?>>()

        val written = MediaDownloader().download(
            url = "$base/media/video.mp4",
            headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to "test-ua"),
            sink = sink,
            onProgress = { done, total -> progress += done to total },
        )

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, sink.toByteArray())
        // 请求头必须原样带上，否则 CDN 403
        // （JDK 的 HttpServer 会把头名归一成「首字母大写」，所以这里统一按小写比对）
        assertTrue(seenHeaders.any { it.equals("referer: https://www.bilibili.com/", ignoreCase = true) })
        assertTrue(seenHeaders.any { it.equals("user-agent: test-ua", ignoreCase = true) })
        // 最后一次回调就是完整长度，进度不会永远停在 97%
        assertEquals(payload.size.toLong(), progress.last().first)
        assertEquals(payload.size.toLong(), progress.last().second)
    }

    @Test
    fun `服务端不给长度时总长度是null`() = runBlocking {
        chunked = true
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long?>>()

        val written = MediaDownloader().download("$base/media/video.mp4", emptyMap(), sink) { done, total ->
            progress += done to total
        }

        assertEquals(payload.size.toLong(), written)
        assertTrue(progress.all { it.second == null })
        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `服务器报错时抛出带状态码的异常`() = runBlocking {
        val error = runCatching {
            MediaDownloader().download("$base/missing.mp4", emptyMap(), ByteArrayOutputStream())
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("404"))
    }

    @Test
    fun `文件名去掉非法字符并补上扩展名`() {
        // 标题里的 "/" 是非法文件名字符，会被换成下划线
        assertEquals("【标题】一半_一半.mp4", downloadableFileName("【标题】一半/一半", "$base/a.mp4"))
        assertEquals("video.mp4", downloadableFileName("", "$base/a.mp4"))
        // 标题已经是 movie.mp4 时不该拼成 movie.mp4.mp4
        assertEquals("movie.mp4", downloadableFileName("movie.mp4", "$base/movie.mp4"))
        assertEquals("video.mp4", downloadableFileName(null, "$base/play/?video_id=v1"))
        assertEquals("x.mkv", downloadableFileName("x", "$base/v.mkv"))
        assertEquals("很长".repeat(30).take(60) + ".mp4", downloadableFileName("很长".repeat(30), "$base/v.mp4"))
        assertEquals("video.mp4", downloadableFileName("   ", "$base/v.mp4"))
    }

    @Test
    fun `HLS和DASH不当作可下载文件`() {
        assertTrue(canDownloadDirectly("$base/media/video.mp4"))
        assertTrue(canDownloadDirectly("https://cdn.example.com/play/?video_id=v1"))
        assertTrue(!canDownloadDirectly("$base/live/index.m3u8"))
        assertTrue(!canDownloadDirectly("$base/stream.mpd"))
    }

    @Test
    fun `mime和可读大小`() {
        assertEquals("video/mp4", mimeTypeOf("a.mp4"))
        assertEquals("video/x-matroska", mimeTypeOf("a.mkv"))
        assertEquals("audio/mpeg", mimeTypeOf("a.mp3"))
        assertEquals("video/mp4", mimeTypeOf("没有扩展名"))
        assertEquals("1 KB", readableSize(1024))
        assertEquals("1.0 MB", readableSize(1024 * 1024))
        assertNull(extensionOf("$base/play/?video_id=1"))
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { seenHeaders += name + ": " + it } }
        if (exchange.requestURI.path != "/media/video.mp4") {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        exchange.responseHeaders.add("Content-Type", "video/mp4")
        exchange.sendResponseHeaders(200, if (chunked) 0 else payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }
}
