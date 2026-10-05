package com.qzkt.timetable.ui.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.qzkt.timetable.ui.WebImportResult
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 网页导入兜底通道。
 *
 * 学校的强智没开放 `app.do` 移动端接口时用这个：在下面这个 WebView 里正常登录，
 * 进到课表页面后点「抓取当前页面」。
 *
 * WebView 有几处必须显式配置，否则"登录成功之后一片白"是必然会发生的：
 * - **`setSupportMultipleWindows(true)` + [WebChromeClient.onCreateWindow]**：
 *   强智登录成功后普遍用 `window.open` 打开主界面。Android WebView 默认
 *   `setSupportMultipleWindows(false)`，这次跳转会被直接丢掉，页面就停在一个空白帧上。
 * - **放行混合内容**：不少教务系统是 https 页面里引 http 资源，默认会被静默拦掉，
 *   表现同样是白屏。
 * - **错误要看得见**：加载失败、证书不通过、HTTP 4xx/5xx 都写到界面上，
 *   而不是让人对着白屏猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebImportScreen(
    baseUrl: String,
    busy: Boolean,
    lastResult: WebImportResult?,
    onBack: () -> Unit,
    onDocuments: (List<String>) -> Unit,
    onDirectFetch: (String, String?) -> Unit,
) {
    val context = LocalContext.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pendingSslHandler by remember { mutableStateOf<SslErrorHandler?>(null) }
    var report by remember { mutableStateOf<CaptureReport?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    /** 页面加载完成但正文是空的 —— 这是"白屏"里最容易被误判成"没加载"的一种。 */
    var blankPageAt by remember { mutableStateOf<String?>(null) }

    /** 是否已经在 https 失败后改试过 http，避免来回重试。 */
    var triedHttpFallback by remember { mutableStateOf(false) }

    /** WebViewClient 看到的每个页面地址（含 iframe），排查"课表到底在哪一层"用。 */
    val visitedUrls = remember { mutableStateListOf<String>() }

    // 地址在页面内也可改：不再依赖上一页表单填没填，避免"带着空地址跳过来"
    var addressInput by remember(baseUrl) { mutableStateOf(baseUrl) }
    var activeUrl by remember(baseUrl) { mutableStateOf(normalizeWebUrl(baseUrl)) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pageTitle.ifBlank { "网页导入" }, fontSize = 15.sp, maxLines = 1)
                        if (currentUrl.isNotBlank()) {
                            Text(
                                text = currentUrl,
                                fontSize = 10.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val view = webView
                        if (view != null && view.canGoBack()) view.goBack() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = "① 先在下面登录教务系统 —— 能看到主界面就是登录成功了\n" +
                    "② 再点最下面的「用当前登录状态直接抓课表」（不用自己找菜单）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )

            AddressRow(
                value = addressInput,
                onValueChange = { addressInput = it },
                onOpen = { activeUrl = normalizeWebUrl(addressInput) },
            )

            pageError?.let { message ->
                ErrorCard(
                    message = message,
                    onRetry = {
                        pageError = null
                        webView?.reload()
                    },
                    onProceedSsl = pendingSslHandler?.let { handler ->
                        {
                            pageError = null
                            pendingSslHandler = null
                            handler.proceed()
                        }
                    },
                )
            }

            blankPageAt?.let { url ->
                BlankPageCard(
                    url = url,
                    onOpenInBrowser = { openInBrowser(context, url) },
                    onReload = {
                        blankPageAt = null
                        webView?.reload()
                    },
                )
            }

            val loadedUrl = activeUrl
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (loadedUrl == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "上面填一下学校地址再点「打开」。\n" +
                                "填站点首页即可，例如 http://jwgl.xxx.edu.cn（程序会自动去掉 /app.do 这类接口后缀）。",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                configureForSchoolSite(this)

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onCapture(payload: String) {
                                            // 回调在 JS 线程上，状态改动一律 post 回主线程
                                            post {
                                                capturing = false
                                                val parsed = parseCapture(payload)
                                                if (parsed == null) {
                                                    pageError = "页面内容拿到了但解析失败"
                                                    showDiagnostics = true
                                                } else {
                                                    report = parsed
                                                    onDocuments(parsed.documents)
                                                }
                                            }
                                        }
                                    },
                                    BRIDGE_NAME,
                                )

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }

                                    /**
                                     * 关键修复：教务系统登录后用 `window.open` 开主界面。
                                     * Android WebView 默认 `setSupportMultipleWindows(false)`，
                                     * 这次跳转会被直接丢掉，用户看到的就是登录后的白屏。
                                     *
                                     * 这里用一个临时 WebView 接住新窗口，拿到地址后转交给主 WebView。
                                     */
                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?,
                                    ): Boolean {
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                        val main = webView ?: return false

                                        val probe = WebView(context).apply {
                                            settings.javaScriptEnabled = true
                                            webViewClient = object : WebViewClient() {
                                                private var handedOff = false

                                                private fun handOff(url: String?) {
                                                    if (handedOff) return
                                                    if (url.isNullOrBlank() || url == "about:blank") return
                                                    handedOff = true
                                                    main.loadUrl(url)
                                                    destroy()
                                                }

                                                override fun onPageStarted(
                                                    probeView: WebView?,
                                                    url: String?,
                                                    favicon: Bitmap?,
                                                ) = handOff(url)

                                                // 有的教务系统是 window.open('') 再 document.write，
                                                // 这种情况下地址一直是 about:blank，只能等页面加载完再看
                                                override fun onPageFinished(probeView: WebView?, url: String?) =
                                                    handOff(url)

                                                override fun shouldOverrideUrlLoading(
                                                    probeView: WebView?,
                                                    request: WebResourceRequest?,
                                                ): Boolean {
                                                    handOff(request?.url?.toString())
                                                    return true
                                                }
                                            }
                                        }
                                        transport.webView = probe
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        progress = 1
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        progress = 100
                                        view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                        if (!url.isNullOrBlank()) {
                                            currentUrl = url
                                            if (visitedUrls.lastOrNull() != url) visitedUrls.add(url)
                                        }
                                        // 只探主页面：这里面能区分"真的空白"和"frameset 没有 body"
                                        if (view != null && url != null && url == view.url) {
                                            probeBlankPage(view, url) { blankPageAt = it }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        progress = 100
                                        val failedUrl = request.url.toString()

                                        // 只填了域名时默认按 https 起手；学校的强智很多还是 http，
                                        // 这里失败一次就自动换协议重来
                                        if (failedUrl.startsWith("https://") && !triedHttpFallback) {
                                            triedHttpFallback = true
                                            webView?.loadUrl(failedUrl.replaceFirst("https://", "http://"))
                                            return
                                        }

                                        pageError = "页面加载失败：${error?.description}（错误码 ${error?.errorCode}）\n$failedUrl"
                                        showDiagnostics = true
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        pageError = "服务器返回 ${errorResponse?.statusCode}：${request.url}"
                                        showDiagnostics = true
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: SslError?,
                                    ) {
                                        pendingSslHandler = handler
                                        pageError = "证书校验没过：${error?.url}\n" +
                                            "学校的自签证书需要先装进手机。确实要用可以点「继续（不安全）」，但那之后这条连接就不再受保护了。"
                                        showDiagnostics = true
                                    }
                                }

                                loadUrl(loadedUrl)
                            }.also { webView = it }
                        },
                    )
                }

                if (capturing || busy) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LinearProgressIndicator(modifier = Modifier.padding(32.dp).fillMaxWidth())
                    }
                }
            }

            // 主推这条路：手机屏幕上看那个桌面版菜单太费劲，直接拿当前登录状态去请求课表页。
            // 放在最上面是因为它是"登录之后的第一步"，按钮顺序要跟操作顺序一致。
            Button(
                onClick = {
                    val base = loadedUrl ?: currentUrl.ifBlank { addressInput }
                    onDirectFetch(base, CookieManager.getInstance().getCookie(base))
                },
                enabled = !busy && !capturing && (loadedUrl != null || currentUrl.isNotBlank()),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            ) {
                Text("用当前登录状态直接抓课表（推荐）", fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { webView?.reload() },
                    enabled = loadedUrl != null && !capturing,
                ) {
                    Text("重新加载")
                }

                OutlinedButton(
                    onClick = {
                        val view = webView ?: return@OutlinedButton
                        capturing = true
                        pageError = null
                        view.evaluateJavascript(CAPTURE_SCRIPT, null)
                    },
                    enabled = loadedUrl != null && !capturing && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("抓取当前页面")
                }
            }

            DiagnosticsPanel(
                expanded = showDiagnostics,
                onToggle = { showDiagnostics = !showDiagnostics },
                currentUrl = currentUrl,
                visitedUrls = visitedUrls.toList(),
                report = report,
                lastResult = lastResult,
                onCopy = { text -> copyToClipboard(context, text) },
            )
        }
    }

    // 地址变了（页面内改了地址并点了「打开」）就重新加载。
    // 用 v.url != target 做判重，避免和 factory 里的首次加载撞车导致加载两次。
    LaunchedEffect(activeUrl, webView) {
        val view = webView ?: return@LaunchedEffect
        val target = activeUrl ?: return@LaunchedEffect
        if (view.url != target) view.loadUrl(target)
    }

    // 抓取是靠 JS 回调回来的；万一页面太大、脚本被拦或回调丢了，不能一直转圈
    LaunchedEffect(capturing) {
        if (!capturing) return@LaunchedEffect
        delay(CAPTURE_TIMEOUT_MS)
        if (capturing) {
            capturing = false
            pageError = "抓取页面超时了：可能是页面太大、或者这段脚本没跑起来。" +
                "可以改用下面的「用当前登录状态直接抓课表」。"
            showDiagnostics = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
            webView = null
        }
    }
}

// ------------------------------------------------------------------ WebView 配置

@SuppressLint("SetJavaScriptEnabled")
private fun configureForSchoolSite(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        loadsImagesAutomatically = true

        // 登录后用 window.open 打开主界面 —— 不开这两项就会停在白屏上
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true

        // https 页面里引 http 资源是教务系统的常态，默认会被静默拦掉
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT

        // 有些教务系统按 UA 拒绝 WebView，把标记里的 wv 去掉
        userAgentString = userAgentString.replace("; wv", "")
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(view, true)
    }
}

// ------------------------------------------------------------------ 抓取脚本

private class CaptureReport(
    val url: String,
    val title: String,
    val frameCount: Int,
    val documents: List<String>,
    val text: String,
) {
    val htmlLength: Int get() = documents.sumOf { it.length }
}

private fun parseCapture(payload: String): CaptureReport? = runCatching {
    val json = JSONObject(payload)
    val docs = json.optJSONArray("documents")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }.orEmpty()

    CaptureReport(
        url = json.optString("url"),
        title = json.optString("title"),
        frameCount = json.optJSONArray("frames")?.length() ?: 0,
        documents = docs,
        text = json.optString("text"),
    ).takeIf { it.documents.isNotEmpty() }
}.getOrNull()

private const val BRIDGE_NAME = "QzBridge"

/** 抓取页面的超时时间：超过就当作没抓到，而不是一直转圈。 */
private const val CAPTURE_TIMEOUT_MS = 8000L

/**
 * 抓取脚本。
 *
 * 有几处是踩过坑才这么写的：
 * - **每份文档单独抓**（顶层的 + 每个同源 iframe 的），不拼成一份 HTML。
 *   拼起来的话，强智登录后的 frameset 主界面会让 HTML 解析器把 iframe 的内容整段丢掉。
 * - **取 `body.innerHTML` 而不是整份 `documentElement.outerHTML`**：少一层 `<html>/<body>` 包裹，
 *   各份文档之间不会互相干扰。
 * - **每份文档截断到 400KB**：这个桥是字符串传参，超大页面会拖慢甚至失败。
 * - **顺手带回 `innerText`**：万一解析不出来，界面上直接能看到页面写了什么。
 */
private val CAPTURE_SCRIPT = """
(function () {
  var documents = [];
  var frames = [];
  var MAX = 400000;

  function grab(doc, label) {
    try {
      var body = doc.body;
      if (!body) { frames.push({ label: label, ok: false, length: 0 }); return; }
      var html = '<html><body>' + body.innerHTML + '</body></html>';
      if (html.length > MAX) html = html.substring(0, MAX);
      documents.push(html);
      frames.push({ label: label, ok: true, length: html.length });
    } catch (e) {
      frames.push({ label: label, ok: false, length: 0 });
    }
  }

  grab(document, 'main');
  try {
    for (var i = 0; i < window.frames.length; i++) {
      grab(window.frames[i].document, 'frame' + i);
    }
  } catch (e) {}

  var text = '';
  try { text = document.body ? document.body.innerText : ''; } catch (e) {}

  window.$BRIDGE_NAME.onCapture(JSON.stringify({
    url: String(location.href),
    title: document.title || '',
    frames: frames,
    documents: documents,
    text: text.substring(0, 4000)
  }));
})();
""".trimIndent()

// ------------------------------------------------------------------ 界面块

@Composable
private fun AddressRow(value: String, onValueChange: (String) -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("学校地址") },
            placeholder = { Text("http://jwgl.xxx.edu.cn") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onOpen, enabled = value.isNotBlank()) { Text("打开") }
    }
}

@Composable
private fun BlankPageCard(url: String, onOpenInBrowser: () -> Unit, onReload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "页面加载完成了，但正文是空的（服务器返回 200，内容为空）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "常见原因：① 地址填成了 app.do 接口地址（那是接口，不是网页）；" +
                    "② 这个系统只允许校内网访问；③ 站点要靠脚本跳转，而脚本没跑起来。" +
                    "点「用系统浏览器打开」可以直接判断是地址问题还是 WebView 的问题。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenInBrowser) { Text("用系统浏览器打开") }
                TextButton(onClick = onReload) { Text("再加载一次") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onProceedSsl: (() -> Unit)?) {    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text("重试") }
                if (onProceedSsl != null) {
                    TextButton(onClick = onProceedSsl) {
                        Text("继续（不安全）", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    currentUrl: String,
    visitedUrls: List<String>,
    report: CaptureReport?,
    lastResult: WebImportResult?,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        TextButton(onClick = onToggle) {
            Text(if (expanded) "收起诊断信息" else "诊断信息（白屏 / 抓不到课时看这里）")
        }

        if (!expanded) return@Column

        val summary = buildString {
            appendLine("当前地址：${currentUrl.ifBlank { "(还没开始加载)" }}")
            appendLine()
            appendLine("访问过的页面（含 iframe）：")
            if (visitedUrls.isEmpty()) {
                appendLine("  （无）")
            } else {
                visitedUrls.takeLast(10).forEach { appendLine("  $it") }
            }
            appendLine()
            if (report == null) {
                appendLine("还没抓取过页面。")
            } else {
                appendLine("最近一次抓取：")
                appendLine("  地址：${report.url}")
                appendLine("  标题：${report.title}")
                appendLine("  iframe 数：${report.frameCount}")
                appendLine("  HTML 长度：${report.htmlLength}")
            }
            lastResult?.let {
                appendLine()
                appendLine("导入结果：${if (it.ok) "成功" else "失败"} · ${it.message}")
            }
            lastResult?.rawSnippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                appendLine()
                appendLine("服务端返回的原始内容（前 1200 字）：")
                appendLine(snippet.take(1200))
            }
            report?.text?.let { text ->
                appendLine()
                appendLine("页面正文（前 800 字）：")
                appendLine(text.take(800))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState()),
                )
                Text(
                    text = "如果课表在跨域 iframe 里，JS 读不到它的内容 —— 对照上面「访问过的页面」列表，把那个地址发我。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onCopy(summary) }) { Text("复制诊断信息") }
            }
        }
    }
}

// ------------------------------------------------------------------ 工具

/**
 * 页面加载完了但正文是空的。
 *
 * `frameset` 页面没有 `body`，不算空白；真的空白往往是：
 * 地址填成了 `app.do`、只允许校内网访问、或者站点把内容全交给了一个加载失败的脚本。
 */
private fun probeBlankPage(view: WebView, url: String, onBlank: (String?) -> Unit) {
    view.evaluateJavascript(BLANK_PROBE_SCRIPT) { raw ->
        val info = decodeProbeResult(raw) ?: return@evaluateJavascript
        onBlank(if (isBlankPage(info, url)) url else null)
    }
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("强智课表诊断信息", text))
}
