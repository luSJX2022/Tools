package com.qzkt.timetable.data.anime

import com.qzkt.timetable.data.net.ipv4First
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class DoubanSubject(
    val id: String,
    val title: String,
    val year: String,
    val type: String,
)

internal class DoubanSynopsisClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .ipv4First()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val cache = mutableMapOf<String, String>()

    @Synchronized
    fun synopsis(title: String, year: String, doubanId: String): String? {
        val cacheKey = listOf(
            doubanId.trim(),
            normalizeDoubanTitle(title),
            year.yearDigits(),
        ).joinToString("|")
        cache[cacheKey]?.let { return it }

        val subject = try {
            directSubject(doubanId)?.let(::fetchSynopsis)
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
        if (!subject.isNullOrBlank()) {
            cache[cacheKey] = subject
            return subject
        }
        val resolved = try {
            val queryUrl = SUBJECT_SUGGEST_URL.newBuilder()
                .addQueryParameter("q", title)
                .build()
            val suggestions = requestArray(queryUrl, SEARCH_REFERER)
            val match = matchDoubanSubject(suggestions, title, year) ?: return null
            fetchSynopsis(match)
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
        if (!resolved.isNullOrBlank()) cache[cacheKey] = resolved
        return resolved
    }

    private fun directSubject(value: String): DoubanSubject? {
        val input = value.trim()
        if (input.isBlank()) return null
        val url = input.toHttpUrlOrNull()
        val pathId = url?.encodedPathSegments?.let { segments ->
            segments.indexOf("subject")
                .takeIf { it >= 0 }
                ?.let { segments.getOrNull(it + 1) }
        }
        val id = pathId ?: input.takeIf { url == null && it.matches(Regex("\\d+")) } ?: return null
        if (!id.matches(Regex("\\d+"))) return null
        val type = if (url?.encodedPathSegments?.contains("tv") == true) TYPE_TV else TYPE_MOVIE
        return DoubanSubject(id = id, title = "", year = "", type = type)
    }

    private fun fetchSynopsis(subject: DoubanSubject): String? {
        val category = if (subject.type == TYPE_TV) TYPE_TV else TYPE_MOVIE
        val referer = "https://m.douban.com/$category/subject/${subject.id}/".toHttpUrl()
        val endpoint = "https://m.douban.com/rexxar/api/v2/$category/${subject.id}?for_mobile=true"
            .toHttpUrl()
        val detail = request(endpoint, referer)
        return detail.optString("intro").takeUnless { it.isBlank() || it == "null" }
            ?: detail.optString("summary").takeUnless { it.isBlank() || it == "null" }
    }

    private fun request(url: HttpUrl, referer: HttpUrl): JSONObject {
        return JSONObject(requestBody(url, referer))
    }

    private fun requestArray(url: HttpUrl, referer: HttpUrl): JSONArray {
        return JSONArray(requestBody(url, referer))
    }

    private fun requestBody(url: HttpUrl, referer: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer.toString())
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("豆瓣简介请求失败（HTTP ${response.code}）")
            response.body?.string() ?: throw IOException("豆瓣简介请求返回空内容")
        }
        return body
    }

    private companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_TV = "tv"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
        val SUBJECT_SUGGEST_URL = "https://movie.douban.com/j/subject_suggest".toHttpUrl()
        val SEARCH_REFERER = "https://movie.douban.com/".toHttpUrl()
    }
}

internal fun matchDoubanSubject(suggestions: JSONArray, title: String, year: String): DoubanSubject? {
    val normalizedTitle = normalizeDoubanTitle(title)
    if (normalizedTitle.isBlank()) return null
    val candidates = (0 until suggestions.length()).mapNotNull { index ->
        val item = suggestions.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val candidateTitle = item.optString("title")
        val type = item.optString("type")
        if (
            !id.matches(Regex("\\d+")) || candidateTitle.isBlank() ||
            type !in setOf("movie", "tv") ||
            normalizeDoubanTitle(candidateTitle) != normalizedTitle
        ) {
            return@mapNotNull null
        }
        DoubanSubject(
            id = id,
            title = candidateTitle,
            year = item.optString("year"),
            type = type,
        )
    }
    if (candidates.isEmpty()) return null
    val normalizedYear = year.yearDigits()
    val yearMatches = candidates.filter { candidate ->
        val candidateYear = candidate.year.yearDigits()
        normalizedYear.isNotBlank() && candidateYear == normalizedYear
    }
    if (normalizedYear.isNotBlank()) return yearMatches.firstOrNull()
    return candidates.firstOrNull { it.type == "movie" } ?: candidates.first()
}

internal fun normalizeDoubanTitle(value: String): String =
    value.lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in TITLE_PUNCTUATION }

private const val TITLE_PUNCTUATION = "·•-—_：:，,。.!！?？'\"()（）[]【】"

private fun String.yearDigits(): String =
    Regex("\\d{4}").find(this)?.value.orEmpty()
