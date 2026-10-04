package com.qzkt.timetable.jw.qz

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 强智各平台适配器共用的网络零件。
 *
 * 两个适配器（app.do 移动端、jsxsd 网页版）除了接口不同，客户端配置和编码处理是一样的。
 */
internal object QzHttp {

    /** 手机端 UA；网页版会按 UA 返回不同布局，所以两边都用移动端 UA 更稳。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; U; Mobile; Android 13; zh-cn) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(InMemoryCookieJar())
        .build()

    /** 会话级的 cookie 存储：登录拿到的 JSESSIONID 之类全靠它。 */
    class InMemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name }
                list += cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    /** 强智有的学校返回 GBK：先按声明的编码解，没声明就按 UTF-8 试，出现替换字符再退回 GBK。 */
    fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val declared = contentType?.let {
            Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        if (declared != null) {
            val cs = runCatching { Charset.forName(declared) }.getOrNull()
            if (cs != null) return String(bytes, cs)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.none { it == '\uFFFD' }) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }

    /** 密码之类的参数不该进日志。 */
    fun redact(url: String): String = url
        .replace(Regex("pwd=[^&]*"), "pwd=***")
        .replace(Regex("userPassword=[^&]*"), "userPassword=***")
        .replace(Regex("encoded=[^&]*"), "encoded=***")
}
