package com.qzkt.timetable.ui.academic

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qzkt.timetable.jw.qz.QzHttp
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 选课页：把学校自己的选课页面嵌进来（WebView，登录会话同步进去免登录）。
 *
 * 各校选课接口差异很大、页面又全是动态脚本，App 自己实现选课 UI 风险太高；
 * 学校的页面永远是对的。这里只做两件事：
 * 1. 从教务主界面菜单里找「选课」入口（[discoverCourseSelectUrl]）；
 * 2. 把登录会话 cookie 同步进 WebView 的 CookieManager，以登录状态直接打开。
 * 找不到选课入口就退回教务主界面 —— 学校菜单里一定有选课，用户自己点一下。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectScreen(
    baseUrl: String,
    sessionCookie: String,
    hasSession: Boolean,
    onOpenWebLogin: () -> Unit = {},
) {
    var targetUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(hasSession && baseUrl.isNotBlank()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(false) }

    LaunchedEffect(baseUrl, sessionCookie, hasSession) {
        if (!hasSession || baseUrl.isBlank()) {
            discovering = false
            return@LaunchedEffect
        }
        discovering = true
        val found = withContext(Dispatchers.IO) { discoverCourseSelectUrl(baseUrl, sessionCookie) }
        // 会话 cookie 同步进 WebView（选课页面靠它免登录）；cookie 里一行一个 name=value
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            sessionCookie.split(";").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        manager.setCookie(baseUrl, "$name=$value; Path=/")
                    }
                }
            }
            manager.flush()
        }
        targetUrl = found
        discovering = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选课") },
                actions = {
                    if (targetUrl != null) {
                        IconButton(onClick = { webView?.reload() }, enabled = !pageLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新加载")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !hasSession -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有登录会话，先去应用内登录一次，选课页面就能免登录打开",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenWebLogin) { Text("去应用内登录") }
            }

            discovering -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            targetUrl != null -> AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoading = false
                            }
                        }
                    }
                },
                update = { view ->
                    webView = view
                    val url = targetUrl
                    if (url != null && view.url != url) view.loadUrl(url)
                },
            )
        }
    }
}

/**
 * 从教务主界面的菜单里找「选课」入口。
 *
 * 各校菜单都长在主界面（xsMainV.htmlx 这一代）里：优先找 href 带 `xsxk`
 * 的链接（强智选课路径的通用特征），找不到再找文字带「选课」的；
 * 相对地址解析成绝对地址，找不到返回 null（调用方退回主界面）。
 */
internal fun discoverCourseSelectUrl(baseUrl: String, cookie: String): String? {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    try {
        for (path in QzJsxsdAdapter.HOME_PATHS) {
            val pageUrl = baseUrl + path
            val body = runCatching {
                client.newCall(
                    Request.Builder().url(pageUrl)
                        .header("User-Agent", QzHttp.USER_AGENT)
                        .header("Cookie", cookie)
                        .get()
                        .build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        null
                    } else {
                        QzHttp.decodeBody(resp.peekBody(Long.MAX_VALUE).bytes(), resp.header("Content-Type"))
                    }
                }
            }.getOrNull() ?: continue
            if (body.isBlank() || body.contains("userAccount")) continue // 被打回登录页

            val doc = Jsoup.parse(body, pageUrl)
            val anchor = doc.select("a[href]").firstOrNull { a ->
                a.attr("href").contains("xsxk", ignoreCase = true) ||
                    a.text().replace(" ", "").contains("选课")
            } ?: continue
            val href = anchor.attr("href").trim()
            if (href.isEmpty() || href.startsWith("javascript", ignoreCase = true)) continue
            // 按浏览器语义解析：相对链接基于页面 URL（含目录），不是基于 /jsxsd 根
            val absolute = pageUrl.toHttpUrlOrNull()?.resolve(href)?.toString() ?: continue
            return absolute
        }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    return null
}
