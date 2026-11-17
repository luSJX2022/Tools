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
    fun `HLS主列表选择最高码率并合并相对地址分片`() = runBlocking {
        val sink = ByteArrayOutputStream()
        val headers = mapOf("Referer" to "https://video.example/")

        val written = MediaDownloader().downloadHls("$base/master.m3u8", headers, sink)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
        assertEquals(4L, written)
        assertTrue(seenHeaders.any { it.equals("referer: https://video.example/", ignoreCase = true) })
    }

    @Test
    fun `HLS加密播放列表明确拒绝`() = runBlocking {
        val error = runCatching {
            MediaDownloader().downloadHls("$base/encrypted.m3u8", emptyMap(), ByteArrayOutputStream())
        }.exceptionOrNull() as LinkResolveException

        assertTrue(error.message!!.contains("加密"))
    }

    @Test
    fun `HLS重定向后按最终播放列表地址解析分片`() = runBlocking {
        val sink = ByteArrayOutputStream()

        MediaDownloader().downloadHls("$base/redirect.m3u8", emptyMap(), sink)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
    }

    @Test
    fun `媒体下载客户端不限制整次下载时长`() {
        assertEquals(0, mediaDownloadClient().callTimeoutMillis)
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
        assertEquals("剧名.ts", downloadableFileName("剧名", "$base/video.m3u8"))
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
        assertEquals("video/mp2t", mimeTypeOf("a.ts"))
        assertEquals("video/mp4", mimeTypeOf("没有扩展名"))
        assertEquals("1 KB", readableSize(1024))
        assertEquals("1.0 MB", readableSize(1024 * 1024))
        assertNull(extensionOf("$base/play/?video_id=1"))
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { seenHeaders += name + ": " + it } }
        when (exchange.requestURI.path) {
            "/master.m3u8" -> return respond(
                exchange,
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nstream/low.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=2000\nstream/high/index.m3u8\n",
                "application/vnd.apple.mpegurl",
            )
            "/redirect.m3u8" -> {
                exchange.responseHeaders.add("Location", "$base/stream/high/index.m3u8")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }
            "/stream/low.m3u8" -> return respond(
                exchange,
                "#EXTM3U\n#EXTINF:2,\nlow.ts\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl",
            )
            "/stream/high/index.m3u8" -> return respond(
                exchange,
                "#EXTM3U\n#EXTINF:2,\npart-1.ts\n#EXTINF:2,\npart-2.ts\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl",
            )
            "/stream/high/part-1.ts" -> return respond(exchange, byteArrayOf(1, 2), "video/mp2t")
            "/stream/high/part-2.ts" -> return respond(exchange, byteArrayOf(3, 4), "video/mp2t")
            "/encrypted.m3u8" -> return respond(
                exchange,
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\npart.ts\n",
                "application/vnd.apple.mpegurl",
            )
        }
        if (exchange.requestURI.path != "/media/video.mp4") {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        exchange.responseHeaders.add("Content-Type", "video/mp4")
        exchange.sendResponseHeaders(200, if (chunked) 0 else payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    private fun respond(exchange: HttpExchange, body: String, contentType: String) =
        respond(exchange, body.toByteArray(Charsets.UTF_8), contentType)

    private fun respond(exchange: HttpExchange, body: ByteArray, contentType: String) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
