package com.qzkt.timetable.data.anime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Internet Archive search restricted to records explicitly marked Public Domain Mark or CC0. */
class InternetArchiveSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) : AnimeSource {
    override val key: String = "archive-pd"
    override val name: String = "Internet Archive 公有领域"
    override val endpointLabel: String = "archive.org · Public Domain Mark / CC0"
    override val categories = listOf("公有领域电影" to CATEGORY_MOVIES)

    override suspend fun list(categoryId: String, page: Int): AnimePage = withContext(Dispatchers.IO) {
        require(categoryId == CATEGORY_MOVIES) { "不支持的 Internet Archive 分类" }
        fetchPage(searchUrl(page.coerceAtLeast(1), null))
    }

    override suspend fun search(keyword: String, page: Int): AnimePage = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        require(query.isNotEmpty()) { "搜索内容不能为空" }
        fetchPage(searchUrl(page.coerceAtLeast(1), query))
    }

    override suspend fun detail(id: String): AnimeDetail = withContext(Dispatchers.IO) {
        require(IDENTIFIER.matches(id)) { "无效的 Internet Archive 条目标识" }
        val record = requestJson("$ARCHIVE_ROOT/metadata/$id")
        val metadata = record.optJSONObject("metadata")
            ?: throw IOException("Internet Archive 条目缺少元数据")
        if (!hasPublicDomainLicense(record.opt("licenseurl"), metadata.opt("licenseurl"))) {
            throw IOException("该条目没有明确的 Public Domain Mark 或 CC0 许可，已拒绝播放")
        }

        val video = selectPublicDomainVideoFile(record.optJSONArray("files"))
            ?: throw IOException("该公有领域条目没有可播放的 MP4/WebM 文件")
        val title = metadata.optString("title").trim().ifBlank { id }
        val description = jsonText(metadata.opt("description"))
        val year = (
            metadata.optString("year").takeIf(String::isNotBlank)
                ?: metadata.optString("date")
            ).let { YEAR.find(it)?.value.orEmpty() }

        AnimeDetail(
            source = key,
            id = id,
            name = title,
            pic = "$ARCHIVE_ROOT/services/img/$id",
            remark = "Public Domain Mark / CC0",
            year = year,
            area = "",
            genre = "公有领域标注",
            content = description,
            synopsisSource = AnimeSynopsisSource.SOURCE_FALLBACK,
            episodes = listOf(
                Episode(
                    label = video.optString("name").substringAfterLast('/').ifBlank { "电影正片" },
                    url = archiveDownloadUrl(id, video.optString("name")),
                ),
            ),
        )
    }

    private suspend fun fetchPage(url: String): AnimePage {
        val response = requestJson(url).optJSONObject("response")
            ?: throw IOException("Internet Archive 搜索响应缺少结果")
        val docs = response.optJSONArray("docs") ?: JSONArray()
        val found = response.optInt("numFound", 0)
        val page = response.optInt("start", 0) / SEARCH_ROWS + 1
        val items = (0 until docs.length()).mapNotNull { index ->
            val doc = docs.optJSONObject(index) ?: return@mapNotNull null
            val identifier = doc.optString("identifier")
            val title = doc.optString("title").trim()
            if (
                !IDENTIFIER.matches(identifier) ||
                title.isBlank() ||
                !hasPublicDomainLicense(doc.opt("licenseurl"))
            ) {
                return@mapNotNull null
            }
            AnimeItem(
                source = key,
                id = identifier,
                name = title,
                pic = "$ARCHIVE_ROOT/services/img/$identifier",
                remark = YEAR.find(doc.optString("year"))?.value.orEmpty(),
            )
        }
        return AnimePage(
            page = page,
            totalPages = ((found + SEARCH_ROWS - 1) / SEARCH_ROWS).coerceAtLeast(1),
            items = items,
        )
    }

    private fun searchUrl(page: Int, keyword: String?): String {
        val query = buildString {
            append("mediatype:movies AND (")
            append(PUBLIC_DOMAIN_MARK_QUERY)
            append(" OR ")
            append(CC0_QUERY)
            append(')')
            if (!keyword.isNullOrBlank()) {
                val escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
                append(" AND (title:\"")
                append(escaped)
                append("\" OR description:\"")
                append(escaped)
                append("\")")
            }
        }
        return "$ARCHIVE_ROOT/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("rows", SEARCH_ROWS.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("output", "json")
            .build()
            .toString()
    }

    private suspend fun requestJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Internet Archive 请求失败（HTTP ${response.code}）")
            response.body.string()
        }
        JSONObject(body)
    }

    private companion object {
        const val CATEGORY_MOVIES = "pd-movies"
        const val ARCHIVE_ROOT = "https://archive.org"
        const val SEARCH_ROWS = 50
        const val PUBLIC_DOMAIN_MARK_QUERY =
            "(licenseurl:\"https://creativecommons.org/publicdomain/mark/1.0/\" OR " +
                "licenseurl:\"http://creativecommons.org/publicdomain/mark/1.0/\")"
        const val CC0_QUERY =
            "(licenseurl:\"https://creativecommons.org/publicdomain/zero/1.0/\" OR " +
                "licenseurl:\"http://creativecommons.org/publicdomain/zero/1.0/\")"
        const val USER_AGENT = "QzktAndroid/1.0 (public-domain catalog)"
        val IDENTIFIER = Regex("[A-Za-z0-9._-]+")
        val YEAR = Regex("\\b\\d{4}\\b")
    }
}

internal fun hasPublicDomainLicense(vararg values: Any?): Boolean =
    values.any { value ->
        licenseValues(value).any { license ->
            license.trim().trimEnd('/').lowercase() in PUBLIC_DOMAIN_LICENSES
        }
    }

internal fun selectPublicDomainVideoFile(files: JSONArray?): JSONObject? {
    if (files == null) return null
    val candidates = (0 until files.length()).mapNotNull { index ->
        val file = files.optJSONObject(index) ?: return@mapNotNull null
        val name = file.optString("name")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (
            extension !in PLAYABLE_EXTENSIONS ||
            file.optString("private").equals("true", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        file
    }
    return candidates.firstOrNull { it.optString("name").endsWith(".mp4", ignoreCase = true) }
        ?: candidates.firstOrNull { it.optString("name").endsWith(".webm", ignoreCase = true) }
}

internal fun archiveDownloadUrl(identifier: String, filename: String): String {
    require(Regex("[A-Za-z0-9._-]+").matches(identifier)) { "无效的 Internet Archive 条目标识" }
    val segments = filename.split('/')
    require(
        filename.isNotBlank() &&
            segments.none { it.isBlank() || it == "." || it == ".." },
    ) { "无效的 Internet Archive 文件名" }
    return "https://archive.org/download".toHttpUrl().newBuilder()
        .addPathSegment(identifier)
        .apply { segments.forEach(::addPathSegment) }
        .build()
        .toString()
}

private fun licenseValues(value: Any?): List<String> = when (value) {
    is String -> listOf(value)
    is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
    else -> emptyList()
}

private fun jsonText(value: Any?): String = when (value) {
    is String -> value.trim()
    is JSONArray -> (0 until value.length())
        .mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        .joinToString("\n\n")
    else -> ""
}

private val PUBLIC_DOMAIN_LICENSES = setOf(
    "https://creativecommons.org/publicdomain/mark/1.0",
    "http://creativecommons.org/publicdomain/mark/1.0",
    "https://creativecommons.org/publicdomain/zero/1.0",
    "http://creativecommons.org/publicdomain/zero/1.0",
)

private val PLAYABLE_EXTENSIONS = setOf("mp4", "webm")
