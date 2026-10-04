package com.qzkt.timetable.ui.web

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 网页导入里两块容易出错、又值得单独测的逻辑。
 *
 * 放在这里（而不是塞在 Composable 文件里）是因为它们跟界面无关，纯函数，好测。
 */

/**
 * 把用户填的学校地址整成 WebView 该打开的地址。
 *
 * 主要防一个坑：配置页那一栏是"学校强智地址"，但用户完全可能把完整的接口地址
 * `http://xxx.edu.cn/app.do` 填进去（毕竟那也确实是"强智的地址"）。
 * 那个地址是接口，用浏览器/WebView 直接打开就是一片空白，所以这里把接口后缀去掉。
 */
internal fun normalizeWebUrl(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null

    text = text.substringBefore('?').substringBefore('#').trimEnd('/')
    text = text.removeSuffix("/app.do").removeSuffix("/App.do").trimEnd('/')
    if (text.isEmpty()) return null

    return if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
}

/**
 * 解析"页面是不是空的"探针返回。
 *
 * `WebView.evaluateJavascript` 回传的是 **JS 返回值的 JSON 编码**。我们的 JS 返回的是一个
 * 字符串（里面又是一层 JSON），所以要解两层：外层 `"{\"text\":0,...}"` 先解成字符串，
 * 再当 JSON 解一次。
 */
internal fun decodeProbeResult(raw: String?): JSONObject? = runCatching {
    val inner = JSONTokener(raw.orEmpty()).nextValue() as? String ?: return@runCatching null
    JSONObject(inner)
}.getOrNull()

/**
 * 判断"加载完了但什么都没有"。
 *
 * `frameset` 页面没有 `body`，`text` 会是 0，但它有 frame，属于正常页面，不能报空白。
 */
internal fun isBlankPage(probe: JSONObject, url: String): Boolean {
    if (url == "about:blank") return false
    return probe.optInt("text", 0) <= 0 && probe.optInt("frames", 0) == 0
}

/** 探针脚本：返回 body 正文长度和 frame 数量。 */
internal val BLANK_PROBE_SCRIPT = """
(function () {
  var text = 0, frames = 0;
  try {
    var body = document.body;
    text = (body && body.innerText) ? body.innerText.trim().length : 0;
  } catch (e) { text = -1; }
  try { frames = window.frames.length; } catch (e) {}
  return JSON.stringify({ text: text, frames: frames, title: document.title || '' });
})();
""".trimIndent()
