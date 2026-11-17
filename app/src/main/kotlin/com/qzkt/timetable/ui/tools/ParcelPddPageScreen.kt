package com.qzkt.timetable.ui.tools

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qzkt.timetable.data.tools.PddExpressApi
import kotlinx.coroutines.delay

/** 「取快递」页面的非微信路由：带 /weixin/ 前缀的会被 302 到微信 OAuth，这个不会。 */
internal const val PDD_HOME_URL = "https://mdkd.pinduoduo.com/package?entry_source=24"

/** 需要用拼多多账号登录时，直接在这个 WebView 里登，Cookie 与原生接口共用。 */
internal const val PDD_LOGIN_URL = "https://mobile.yangkeduo.com/login.html"

/**
 * 把拼多多的「取快递」页面用 WebView 嵌进菜鸟包裹。
 *
 * 与原生「我的快递 / 收件码」互补：原生接口只拿得到结构化数据，
 * 页面里还有物流详情、取件记录、身份码入口等，直接嵌页面最省事。
 * Cookie 与 [ParcelLoginScreen] 共用同一个 WebView Cookie 仓库，登录一次两边都认。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelPddPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(PDD_HOME_URL) }
    var progress by remember { mutableIntStateOf(0) }
    var blockedHint by remember { mutableStateOf<String?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 拼多多 H5 在非微信/非拼多多 App 容器里常常一直转圈（官方限制），12 秒还在加载就提示用户回原生界面
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(12_000)
        if (progress < 100) stalled = true
    }

    /** 深链（pinduoduo:// 等）一律拦掉，否则 WebView 会显示 ERR_UNKNOWN_URL_SCHEME 白屏。 */
    fun intercept(target: String?): Boolean {
        val url = target.orEmpty()
        if (!isDeepLinkScheme(url)) return false
        blockedHint = "已忽略跳转（WebView 打不开）：" + url.substringBefore('?').take(70)
        Toast.makeText(context, "页面试图打开拼多多 App，已忽略", Toast.LENGTH_SHORT).show()
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拼多多") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.loadUrl(PDD_HOME_URL) }) {
                        Icon(Icons.Default.Home, contentDescription = "回到取快递页")
                    }
                    IconButton(onClick = { webView?.loadUrl(PDD_LOGIN_URL) }) {
                        Icon(Icons.Default.VpnKey, contentDescription = "拼多多登录")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            // 官方限制：这个页面在微信/拼多多 App 外的容器里不一定能加载出来，明说比让用户干等好
            if (stalled) {
                Text(
                    "页面在 App 内一直加载不出来（拼多多官方限制）。取快递和身份码请用原生界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                TextButton(onClick = onBack) { Text("返回原生界面") }
            }
            pageError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                TextButton(onClick = { pageError = null; webView?.loadUrl(currentUrl) }) { Text("重试") }
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
                        // 与接口请求、登录页用同一个 UA，避免被当成异常客户端
                        settings.userAgentString = PddExpressApi.UA
                        // 深色模式下让网页也跟随系统/App 的深色（API 33+ 用 isAlgorithmicDarkeningAllowed）
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            settings.isAlgorithmicDarkeningAllowed = true
                        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                            @Suppress("DEPRECATION")
                            settings.forceDark = android.webkit.WebSettings.FORCE_DARK_AUTO
                        }
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
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
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
                        loadUrl(PDD_HOME_URL)
                    }.also { webView = it }
                },
            )
        }
    }
}
