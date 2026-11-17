package com.qzkt.timetable.cidaren

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 四级词库的本地状态：错题本、自定义词库、AI 设置、做题统计。
 *
 * 工程一贯用「JSON 文件持久化 + 手写依赖容器」（不用 Room / Hilt），这里沿用：
 * 落一个 cidaren.json 在 filesDir 下，内容变了就整体写回（数据量很小，没必要做增量）。
 */
class CidarenStore(context: Context) {

    data class Wrong(val stem: String, val answerText: String, val typeLabel: String, val time: String)

    data class State(
        val wrong: List<Wrong> = emptyList(),
        val custom: List<CidarenWord> = emptyList(),
        val modelId: String = DEFAULT_MODEL_ID,
        val apiKey: String = "",
        val answered: Int = 0,
        val correct: Int = 0,
    ) {
        companion object {
            /** 默认用免 Key 的公共模型：装上就能点「AI 深度解析」，不用先配置。 */
            const val DEFAULT_MODEL_ID = "pollinations-openai"
        }
    }

    private val file = File(context.filesDir, "cidaren.json")

    fun load(): State {
        if (!file.exists()) return State()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            State(
                wrong = json.optJSONArray("wrong").mapItems { w ->
                    Wrong(w.optString("stem"), w.optString("answerText"), w.optString("typeLabel"), w.optString("time"))
                },
                custom = json.optJSONArray("custom").mapItems { o ->
                    CidarenWord(
                        w = o.optString("w"),
                        ph = o.optString("ph"),
                        pos = o.optString("pos"),
                        cn = o.optString("cn"),
                        level = o.optString("level", "自定义"),
                        note = o.optString("note"),
                        custom = true,
                    )
                },
                modelId = json.optString("modelId", State.DEFAULT_MODEL_ID),
                apiKey = json.optString("apiKey"),
                answered = json.optInt("answered"),
                correct = json.optInt("correct"),
            )
        } catch (e: Exception) {
            State()
        }
    }

    fun save(state: State) {
        try {
            val json = JSONObject().apply {
                put("wrong", JSONArray().apply {
                    state.wrong.forEach {
                        put(JSONObject().put("stem", it.stem).put("answerText", it.answerText).put("typeLabel", it.typeLabel).put("time", it.time))
                    }
                })
                put("custom", JSONArray().apply { state.custom.forEach { put(it.toJson()) } })
                put("modelId", state.modelId)
                put("apiKey", state.apiKey)
                put("answered", state.answered)
                put("correct", state.correct)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 本地缓存写不进去不该影响使用
        }
    }

    fun clearPluginData() {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("无法删除词达人本地数据")
        }
    }
}

private inline fun <T> JSONArray?.mapItems(crossinline f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
    return out
}
