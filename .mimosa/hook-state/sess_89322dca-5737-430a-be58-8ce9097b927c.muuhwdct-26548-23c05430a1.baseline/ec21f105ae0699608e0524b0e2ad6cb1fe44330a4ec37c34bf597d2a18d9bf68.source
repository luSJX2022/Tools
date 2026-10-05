package com.qzkt.timetable.ui.player.link

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 解析分享链接用的 HTTP 零件。
 *
 * 只负责「分享链接 → 一条能播的地址」这一步；拿到地址之后播放走的是
 * [com.qzkt.timetable.ui.player.PlaybackService] 里那套数据源。
 */
internal fun defaultLinkClient(): OkHttpClient = OkHttpClient.Builder()
    // B站要看 Cookie 里的 buvid3 才肯给接口数据（见 LinkCookieJar）
    .cookieJar(LinkCookieJar())
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    // 短链（b23.tv / v.douyin.com）要靠跳转才拿到真地址，必须允许重定向
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

/** 一次请求的结果；[finalUrl] 是跟完跳转之后的地址 —— 短链的信息都在那上面。 */
internal class LinkResponse(val finalUrl: String, val code: Int, val body: String)

/**
 * 把接口返回整成 JSON。
 *
 * 风控、需要验证、接口改版时，B站 / 抖音会直接甩一个 HTML 页面回来。这时如果只让
 * `JSONObject(body)` 抛异常，界面上就只有一句谁也看不懂的
 * 「Value <!DOCTYPE of type java.lang.String cannot be converted to JSONObject」。
 * 这里换成一句人话，并带上返回内容的开头，一眼能看出是网页还是接口。
 */
internal fun jsonOf(body: String, what: String): JSONObject =
    try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw LinkResolveException(
            what + "返回的是网页而不是接口数据（可能被风控或需要验证）：" +
                body.trim().take(100).replace(WHITESPACE, " "),
            e,
        )
    }

private val WHITESPACE = Regex("\\s+")

/** 换反爬 Cookie 之类的 POST（响应头里的 Set-Cookie 会进 CookieJar，body 一般用不上）。 */
internal fun OkHttpClient.postJson(url: String, json: String, headers: Map<String, String>): LinkResponse {
    val builder = Request.Builder()
        .url(url)
        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
    }
    return newCall(builder.build()).execute().use { response ->
        LinkResponse(
            finalUrl = response.request.url.toString(),
            code = response.code,
            body = response.body.string(),
        )
    }
}

internal fun OkHttpClient.fetch(url: String, headers: Map<String, String>): LinkResponse {
    val builder = Request.Builder().url(url)
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
    }
    return newCall(builder.build()).execute().use { response ->
        LinkResponse(
            finalUrl = response.request.url.toString(),
            code = response.code,
            body = response.body.string(),
        )
    }
}
