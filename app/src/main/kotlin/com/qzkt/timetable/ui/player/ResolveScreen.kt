package com.qzkt.timetable.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qzkt.timetable.data.anime.AnimePlayRequest
import com.qzkt.timetable.ui.player.link.MediaLinkResolver
import com.qzkt.timetable.ui.player.link.detectShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 把用户填的流媒体地址整成能用的 URL。
 *
 * 只填域名/路径时补 https；按后缀在 Media3 那边会自动选解析器
 * （`.m3u8` → HLS、`.mpd` → DASH、其余按普通媒体流）。
 */
private fun normalizeStreamUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return if (text.startsWith("http://", true) || text.startsWith("https://", true) ||
        text.startsWith("rtsp://", true) || text.startsWith("rtmp://", true)
    ) {
        text
    } else {
        "https://$text"
    }
}

/**
 * 链接解析：从播放器里单独拆出来的入口。
 *
 * B站 / 抖音的**分享链接**（短链、或者夹在一堆说明文字里的地址）先联网换成一条
 * 真实地址再交给播放器；普通流地址（`.m3u8` / 直链）不走解析直接播。
 * 防盗链站可以在「请求头」里补 Referer / User-Agent，见 [StreamHeaders]。
 *
 * 解析成功后跳到播放器页播放（播放器本身不在工具页显示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    onBack: () -> Unit,
    onPlay: (AnimePlayRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val linkResolver = remember { MediaLinkResolver() }
    val keyboard = LocalSoftwareKeyboardController.current

    var urlInput by rememberSaveable { mutableStateOf("") }
    var headersInput by rememberSaveable { mutableStateOf("") }
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    // 分享链接要先联网才能换到真实地址，解析期间按钮显示「解析中…」，防止重复点
    var resolving by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun resolveAndPlay() {
        if (urlInput.isBlank() || resolving) return
        val raw = urlInput.trim()
        val userHeaders = parseHeaderBlock(headersInput)
        keyboard?.hide()

        val link = detectShareLink(raw)
        if (link == null) {
            // 不是分享链接，就当普通流地址处理
            val url = normalizeStreamUrl(raw)
            if (url == null) {
                lastError = "看不出来这是个链接，检查一下再试"
                return
            }
            StreamHeaders.set(userHeaders)
            urlInput = ""
            onPlay(AnimePlayRequest(url = url, title = ""))
            return
        }

        // 分享链接：先联网换成一条真实地址，再把平台要求的请求头一起交给播放器
        scope.launch {
            resolving = true
            lastError = null
            try {
                val media = linkResolver.resolve(raw, userHeaders)
                StreamHeaders.set(media.headers)
                urlInput = ""
                onPlay(
                    AnimePlayRequest(
                        url = media.url,
                        title = media.title ?: raw.take(40),
                    ),
                )
            } catch (e: CancellationException) {
                throw e   // 页面被销毁时的正常取消，不算解析失败
            } catch (e: Exception) {
                lastError = "解析失败：" + (e.message ?: e.toString())
            } finally {
                resolving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链接解析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("视频链接 / 分享链接", style = MaterialTheme.typography.bodySmall) },
                placeholder = {
                    Text(
                        "B站、抖音的分享链接，或 https://…/index.m3u8",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { resolveAndPlay() }),
                modifier = Modifier.fillMaxWidth(),
            )

            // 防盗链：不少站要带 Referer / User-Agent 才给数据，默认收起
            val headerCount = remember(headersInput) { parseHeaderBlock(headersInput).size }
            TextButton(
                onClick = { headersExpanded = !headersExpanded },
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    text = if (headerCount == 0) "请求头（防盗链用）" else "请求头（已填 $headerCount 条）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (headersExpanded) {
                OutlinedTextField(
                    value = headersInput,
                    onValueChange = { headersInput = it },
                    label = { Text("每行一个「名称: 值」", style = MaterialTheme.typography.bodySmall) },
                    placeholder = {
                        Text(
                            "Referer: https://www.example.com/\n" +
                                "User-Agent: Mozilla/5.0 (Linux; Android 16)\n" +
                                "Cookie: SESSDATA=…（B站 1080P 需要）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { resolveAndPlay() },
                enabled = urlInput.isNotBlank() && !resolving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (resolving) "解析中…" else "解析并播放")
            }

            Text(
                text = "支持 B站 / 抖音 的分享链接（短链、带说明文字都行），解析后直接跳到播放器；" +
                    "普通流地址（.m3u8 / 直链）不走解析直接播。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lastError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
