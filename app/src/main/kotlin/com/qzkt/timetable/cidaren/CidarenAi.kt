package com.qzkt.timetable.cidaren

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** AI 解析失败时抛出；[message] 已经是能直接给用户看的人话。 */
class CidarenAiException(message: String) : Exception(message)

/** 一次解析的结果：[modelName] 是真正干活的模型，[tried] 一共试了几个（免 Key 模型会自动回退）。 */
data class Outcome(val text: String, val modelName: String, val tried: Int)

/** 单次探测的结果；[message] 为失败原因，成功时是空串。 */
data class ProbeResult(val ok: Boolean, val millis: Long, val message: String)

/**
 * 网页版 cidaren/js/ai.js 的原生移植：免费模型目录 + OpenAI 兼容接口调用。
 *
 * 这里全部是阻塞函数，调用方自己放到 IO 线程；网页版那条「file:// 页面走 CORS 会挂」的
 * 代理链路在原生端没有意义，所以统一直连模型自己的 [CidarenFreeModel.url]。
 */
object CidarenAi {

    /** 与 ai.js 的 SYS 逐字一致：网页端和原生端问同一个模型要同样的结构，不能有两套提示词。 */
    private val SYS = listOf(
        "你是大学英语四六级词汇教师，专门讲解「词达人」（外教社词达人）英语词汇竞赛题目。",
        "请针对用户给出的题目输出简洁、准确的中文解析，固定按以下结构：",
        "1. 正确答案（字母 + 内容）",
        "2. 句意 / 题干翻译",
        "3. 逐项分析（A-D 每个选项：词性、词义、为什么对或错）",
        "4. 考点与常用搭配",
        "5. 记忆技巧 / 易混辨析",
        "不要寒暄；如果无法确定答案，请明确说明不确定之处。",
    ).joinToString("\n")

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 公共免费服务响应方差很大，read 给到 120s；callTimeout 必须比 read 更长才能等到它。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    /* ---------------- 对外能力 ---------------- */

    /**
     * 让当前模型解析题目；当前模型免 Key 时，失败会依次回退到目录里其它免 Key 模型
     * （公共免费服务经常个别限流），所以 [Outcome.tried] 可能大于 1。
     */
    fun explain(
        models: List<CidarenFreeModel>,
        activeId: String?,
        apiKey: String,
        question: String,
        localHint: String,
    ): Outcome {
        val active = models.firstOrNull { it.id == activeId }
        // 与 ai.js 的 ready() 对齐：没有可用端点就没有链可发，直接给设置页的提示。
        if (active == null || active.url.isBlank() || active.model.isBlank() || (!active.noKey && apiKey.isBlank())) {
            throw CidarenAiException("还没有可用的模型：在「设置 → 免费模型」里选一个（免 Key 的可以直接用）")
        }

        val chain: List<CidarenFreeModel> = if (active.noKey) {
            listOf(active) + models.filter { it.noKey && it.id != active.id }
        } else {
            // 需要 Key 的模型不互相回退：Key 只对用户自己选的那家有效。
            listOf(active)
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYS))
            .put(JSONObject().put("role", "user").put("content", buildUserContent(question, localHint)))

        val errs = ArrayList<String>(chain.size)
        chain.forEachIndexed { i, m ->
            try {
                val payload = JSONObject()
                    .put("model", m.model)
                    .put("temperature", 0.2)
                    .put("messages", messages)
                val text = post(m.url, if (m.noKey) "" else apiKey, m.noKey, payload)
                return Outcome(text, m.name, i + 1)
            } catch (e: Exception) {
                errs += m.name + " → " + (e.message ?: e.toString())
            }
        }

        // 文案照抄 ai.js：只列前 4 条，后面附三条出路。
        throw CidarenAiException(
            "内置的免费模型这次都没成功（公共免费服务有速率限制）：\n" +
                errs.take(4).joinToString("\n") + "\n" +
                "建议：① 稍后重试；② 用本地模型（设置 → 免费模型 → 本地模型，装 Ollama 完全免费不限量）；③ 注册一个免费额度服务（如智谱 GLM-4-Flash 永久免费）。"
        )
    }

    /** 用当前配置发一个极小的请求，验证这个模型是否可用。 */
    fun probe(m: CidarenFreeModel, apiKey: String): ProbeResult {
        val t0 = System.currentTimeMillis()
        val payload = JSONObject()
            .put("model", m.model)
            .put("max_tokens", 8)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "回复：ok")))
        return try {
            post(m.url, if (m.noKey) "" else apiKey, m.noKey, payload)
            ProbeResult(true, System.currentTimeMillis() - t0, "")
        } catch (e: Exception) {
            ProbeResult(false, System.currentTimeMillis() - t0, e.message ?: e.toString())
        }
    }

    /** 一键寻找可用模型：按目录顺序串行探测，取第一个成功的（需要 Key 的只在 apiKey 非空时试）。 */
    fun autoPick(models: List<CidarenFreeModel>, apiKey: String): Pair<CidarenFreeModel, ProbeResult>? {
        for (m in models) {
            if (!m.noKey && apiKey.isBlank()) continue
            val r = probe(m, apiKey)
            if (r.ok) return m to r
        }
        return null
    }

    /* ---------------- HTTP ---------------- */

    /** 发一次 chat/completions，返回 choices[0].message.content。 */
    private fun post(url: String, apiKey: String, noKey: Boolean, payload: JSONObject): String {
        val response = try {
            val builder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
            // 免 Key 的公共模型带上 Authorization 反而会被网关拒掉，所以只有「要 Key 且填了 Key」才加。
            if (!noKey && apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            client.newCall(builder.build()).execute()
        } catch (e: IllegalArgumentException) {
            throw CidarenAiException("接口地址不合法：$url")
        } catch (e: IOException) {
            throw CidarenAiException("连接 $url 失败：" + (e.message ?: e.javaClass.simpleName) + "（检查网络，或换一个模型）")
        }
        val (code, body) = response.use { it.code to it.body.string() }
        if (code !in 200..299) throw CidarenAiException(fail(code, body))
        return parseCompletion(body)
    }

    /** 对齐 ai.js 的 fail()：把状态码翻译成用户能照着做的提示。 */
    private fun fail(status: Int, body: String): String {
        val detail = try {
            val j = JSONObject(body)
            val err = j.opt("error")
            val d = when {
                err == null || err == JSONObject.NULL -> ""
                err is JSONObject -> err.optString("message")
                else -> err.toString()
            }
            d.ifBlank { j.optString("message") }
        } catch (e: Exception) {
            body.take(200)
        }
        return when (status) {
            402 -> "该免费模型已限流（402 Payment Required）。可以：① 稍后重试或换一个免 Key 模型；② 用本地模型（完全免费不限量）；③ 注册一个免费额度服务（如智谱 GLM-4-Flash 永久免费）。"
            401 -> "API Key 无效或未授权（401）。免 Key 模型不需要 Key，若你启用了需要 Key 的模型，请在设置里填对。"
            403 -> "被拒绝（403）：该服务可能限制来源、额度用尽或需要有效 Key。"
            404 -> "接口地址或模型名不对（404）：$detail"
            429 -> "请求过于频繁（429）：免费额度限流，稍后再试。"
            502 -> detail.ifBlank { "本地代理转发失败（502）" }
            else -> "（HTTP $status）$detail"
        }
    }

    /** 对齐 ai.js 的 parseCompletion()：不是 JSON、或没有 content，都算失败。 */
    private fun parseCompletion(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw CidarenAiException("返回内容不是 JSON：" + body.take(200))
        }
        // content 可能是 JSON null，用 as? String 兜住，别把 "null" 当成答案。
        val out = (json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content") as? String).orEmpty()
        if (out.isBlank()) throw CidarenAiException("模型没有返回内容：" + body.take(200))
        return out
    }

    /** 对齐 ai.js 的 buildUserContent()：题目 + 可选的本地引擎初判 + 固定收尾。 */
    private fun buildUserContent(question: String, localHint: String): String {
        val lines = ArrayList<String>(6)
        lines += "【题目】"
        lines += question
        if (localHint.isNotBlank()) {
            // 本地引擎的结论不可靠，明确标出来，免得模型被它带偏。
            lines += ""
            lines += "【本地引擎的初步判断（仅供参考，可能有误）】"
            lines += localHint
        }
        lines += ""
        lines += "请给出权威解析。"
        return lines.joinToString("\n")
    }
}
