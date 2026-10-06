package com.qzkt.timetable.jw.qz

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 新一代强智登录页（xsdrmLogin.jsp「教务一体化」这代）的纯 HTTP 复刻 —— 账号密码自动登录。
 *
 * 学校登录页的 submitForm1() 实测（海都学院，浏览器里验证过）：
 * ```
 * encoded = encodeInp(账号) + "%%%" + encodeInp(密码)   // encodeInp 就是 Base64（conwork.js）
 * POST {base}/xk/LoginToXk { loginMethod=LoginToXk, userPassword="", encoded=... }
 * ```
 *
 * 老流程的 Logon.do?flag=sess（scode/sxh 混淆，见 [QzJsxsdLogin]）在这代登录页已经
 * 不参与登录 —— 直接调它只会拿到 `{"flag1":2,"msgContent":"请先登录系统"}`，
 * 这就是以前「账号密码登不进去、有反自动化校验」的真正原因。
 *
 * cookie 走传入 client 的 CookieJar：先 GET 登录页把 JSESSIONID 建起来，登录成功后
 * 继续用同一个 client 就是登录态；需要手动带 cookie 的调用方（比如 [QzJsxsdDirect]）
 * 从返回值里拿拼好的 Cookie 串。
 */
object QzJsxsdAutoLogin {

    /**
     * 尝试登录；成功返回「name=value; …」的 Cookie 串，失败（密码错 / 页面不认识）返回 null。
     *
     * 阻塞式网络调用，调用方需自行放在 IO 线程（和 [QzJsxsdDirect] 的 get/post 同一约定）。
     */
    fun login(client: OkHttpClient, base: String, username: String, password: String): String? =
        runCatching {
                val loginUrl = "$base/xsdrm/xsdrmLogin.jsp"
                val encoder = java.util.Base64.getEncoder()

                // ① 登录页：把会话 cookie（JSESSIONID / SERVERID）建起来，进 CookieJar
                client.newCall(
                    Request.Builder().url(loginUrl).header("User-Agent", QzHttp.USER_AGENT).get().build(),
                ).execute().use { resp -> resp.body?.string() }

                // ② encoded = base64(账号)%%%base64(密码)，提交登录表单
                val form = FormBody.Builder()
                    .add("loginMethod", "LoginToXk")
                    .add("userAccount", username)
                    .add("userPassword", "")
                    .add(
                        "encoded",
                        encoder.encodeToString(username.toByteArray()) + "%%%" +
                            encoder.encodeToString(password.toByteArray()),
                    )
                    .build()

                client.newCall(
                    Request.Builder().url("$base/xk/LoginToXk")
                        .header("User-Agent", QzHttp.USER_AGENT)
                        .header("Referer", loginUrl)
                        .post(form)
                        .build(),
                ).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    // 响应还是登录页 = 账号或密码不对
                    if (body.contains("userAccount") || body.contains("loginForm")) {
                        return@use null
                    }
                    cookiesOf(client, base)
                }
            }.getOrNull()

    /** 从 client 的 CookieJar 里把 base 这台主机的 cookie 拼成请求头用的字符串。 */
    fun cookiesOf(client: OkHttpClient, base: String): String? {
        val url = base.toHttpUrlOrNull() ?: return null
        return client.cookieJar.loadForRequest(url)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }
    }
}
