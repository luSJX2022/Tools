package com.qzkt.timetable.ui.tools

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qzkt.timetable.data.tools.PddAuth
import com.qzkt.timetable.data.tools.PddExpressApi

private const val LOGIN_URL = "https://mobile.yangkeduo.com/login.html"

/**
 * 应用内拼多多登录（手机号 + 验证码），登录完成后把 Cookie 交给调用方。
 *
 * 为什么不走微信：「取快递」里带 /weixin/ 前缀的路由会 302 到微信 OAuth，App 里做不到；
 * 但接口本身只认拼多多自己的登录 Cookie，所以用拼多多网页登录拿 Cookie 就够了。
 *
 * 这个页面必须拦「深链」：拼多多登录页会试图跳 pinduoduo://com.xunmeng.pinduoduo/... 拉起 App，
 * WebView 打不开这种 scheme，会直接显示 ERR_UNKNOWN_URL_SCHEME 把登录页顶掉（真机上踩到过）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: (cookie: String) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(LOGIN_URL) }
    var progress by remember { mutableIntStateOf(0) }
    var loggedIn by remember { mutableStateOf(false) }
    var blockedHint by remember { mutableStateOf<String?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 登录页已经在个人中心、但 App 还没认出来时，把抓到的 Cookie 名摆出来方便排查
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var cookiePreview by remember { mutableStateOf("") }

    fun recheckLogin() {
        loggedIn = PddAuth(harvestPddCookie()).ready
    }

    fun save() {
        val cookie = harvestPddCookie()
        val auth = PddAuth(cookie)
        if (!auth.ready) {
            val names = cookie.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
            cookiePreview = cookie
            diagnostic = "当前抓到 " + names.size + " 个 Cookie：" +
                (if (names.isEmpty()) "（没有）" else names.joinToString("、")) +
                "\n\n需要 PDDAccessToken（或 accesstoken）和 pdd_user_id。" +
                "如果页面已经是个人中心、这里却列不出这两个，点「复制 Cookie」把内容发给开发者排查。"
            return
        }
        onLoggedIn(cookie)
    }

    /** 深链一律拦下，并把提示写出来；http(s) 才让 WebView 自己加载。 */
    fun intercept(target: String?): Boolean {
        val url = target.orEmpty()
        if (!isDeepLinkScheme(url)) return false
        blockedHint = "已忽略跳转（WebView 打不开）：" + url.substringBefore('?').take(70)
        recheckLogin()
        Toast.makeText(context, "页面试图打开拼多多 App，已忽略；请继续在页面内用手机号登录", Toast.LENGTH_SHORT).show()
        return true
    }

    diagnostic?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnostic = null },
            title = { Text("还没识别到登录态") },
            text = {
                Text(text, style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("pdd-cookie", cookiePreview))
                    Toast.makeText(context, "Cookie 已复制", Toast.LENGTH_SHORT).show()
                    diagnostic = null
                }) { Text("复制 Cookie") }
            },
            dismissButton = { TextButton(onClick = { diagnostic = null }) { Text("关闭") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拼多多登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) {
                        Text(if (loggedIn) "保存登录态" else "完成登录")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (loggedIn) {
                Text(
                    "已检测到拼多多登录态，点右上角「保存登录态」即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            blockedHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            pageError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                TextButton(onClick = { pageError = null; retryLoad(webView, context) }) { Text("重试") }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // 与接口请求用同一个 UA，避免登录态和接口调用被认成两个客户端
                        settings.userAgentString = PddExpressApi.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                                intercept(request?.url?.toString())

                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                                intercept(url)

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageError = null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                currentUrl = url ?: currentUrl
                                CookieManager.getInstance().flush()
                                recheckLogin()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                // 深链已经被上面拦掉，这里只剩真正的加载失败（没网、DNS 等）
                                if (request?.isForMainFrame == true) {
                                    pageError = "页面加载失败：" + (error?.description ?: "网络错误")
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }.also { webView = it }
                },
            )
        }
    }
}

/** 重试：WebView 已经在白屏页时直接重新加载原地址。 */
private fun retryLoad(webView: WebView?, context: Context) {
    if (webView == null) {
        Toast.makeText(context, "网页还没准备好，请返回重进", Toast.LENGTH_SHORT).show()
        return
    }
    webView.loadUrl(LOGIN_URL)
}

/**
 * 判断是不是「深链」：除网页协议外的 scheme（pinduoduo://、intent://、weixin:// 等）都要拦。
 * 纯函数，便于单测。相对地址（没有 scheme）属于网页内部跳转，返回 false。
 */
internal fun isDeepLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', "").trim().lowercase()
    if (scheme.isEmpty()) return false
    return scheme !in WEB_SCHEMES
}

private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "javascript", "chrome")

/** 从 WebView 的 Cookie 库里把拼多多几个域的 Cookie 合起来。 */
internal fun harvestPddCookie(): String {
    val manager = CookieManager.getInstance()
    manager.flush()
    return mergeCookieStrings(
        manager.getCookie("https://mdkd.pinduoduo.com/"),
        manager.getCookie("https://mobile.pinduoduo.com/"),
        manager.getCookie("https://pinduoduo.com/"),
        manager.getCookie("https://mobile.yangkeduo.com/"),
        manager.getCookie("https://yangkeduo.com/"),
    )
}

/** 合并多段 Cookie；同名以后出现的为准（后面的域更贴近接口主机）。 */
internal fun mergeCookieStrings(vararg cookieHeaders: String?): String {
    val map = LinkedHashMap<String, String>()
    cookieHeaders.forEach { header ->
        header.orEmpty().split(';').forEach { part ->
            val item = part.trim()
            if (item.isEmpty()) return@forEach
            val name = item.substringBefore('=', "").trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isNotEmpty()) map[name] = value
        }
    }
    return map.entries.filter { it.value.isNotEmpty() }.joinToString("; ") { it.key + "=" + it.value }
}
