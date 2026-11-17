package com.qzkt.timetable.cidaren

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 词根词缀的一条（词素 + 含义）。 */
data class CidarenRoot(val p: String, val m: String)

/** 例句。 */
data class CidarenExample(val en: String, val cn: String)

/** 易混词。 */
data class CidarenConfuse(val w: String, val d: String)

/** 一个词条。自定义词条的 [custom] 为 true，用来在界面上区分。 */
data class CidarenWord(
    val w: String,
    val ph: String = "",
    val pos: String = "",
    val cn: String = "",
    val level: String = "",
    val tag: List<String> = emptyList(),
    val root: List<CidarenRoot> = emptyList(),
    val note: String = "",
    val colloc: List<String> = emptyList(),
    val ex: CidarenExample = CidarenExample("", ""),
    val confuse: List<CidarenConfuse> = emptyList(),
    val custom: Boolean = false,
)

/** 题库里一道题自带的解析。 */
data class CidarenExplain(
    val kp: String = "",
    val sentence: String = "",
    val items: List<String> = emptyList(),
    val extra: String = "",
)

/** 题库里一道题。 */
data class CidarenQuestion(
    val id: String,
    val type: String,
    val stem: String,
    val options: List<String>,
    val answer: Int,
    val target: String = "",
    val level: String = "",
    val explain: CidarenExplain = CidarenExplain(),
)

/** 内置免费模型目录里的一条。 */
data class CidarenFreeModel(
    val id: String,
    val group: String,
    val name: String,
    val url: String,
    val model: String,
    val noKey: Boolean,
    val note: String = "",
    val probe: String = "",
    val regUrl: String = "",
)

/** 免费模型分组。 */
data class CidarenModelGroup(val id: String, val name: String, val note: String = "")

/** 整个数据包：词库 + 题库 + 免费模型目录。 */
data class CidarenData(
    val version: String = "",
    /** 精讲词条（合并前的那 851 条，带搭配/例句/词根词缀）。 */
    val rich: List<CidarenWord> = emptyList(),
    /** 精讲 + 四级合并后的完整词库（引擎与界面用的就是这一份）。 */
    val words: List<CidarenWord> = emptyList(),
    /** 完整四级词库（只带音标、词性、释义）。[words] 里已并入去重后的它们，这里留着是为了界面能显示规模。 */
    val cet4: List<CidarenWord> = emptyList(),
    val questions: List<CidarenQuestion> = emptyList(),
    val groups: List<CidarenModelGroup> = emptyList(),
    val models: List<CidarenFreeModel> = emptyList(),
) {
    companion object {
        const val ASSET = "cidaren/data.json"

        /** 完整四级词库（4536 词，含音标与中文释义）。 */
        const val CET4_ASSET = "cidaren/cet4.json"

        fun load(context: Context, asset: String = ASSET, cet4Asset: String = CET4_ASSET): CidarenData {
            val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cet4Text = runCatching {
                context.assets.open(cet4Asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
            return fromJson(text, cet4Text)
        }

        /**
         * 精讲词条 + 四级词库合成一份：[rich] 在前（同名以精讲为准，因为它带搭配/例句/词根），
         * 四级表里多出来的补在后面。界面和引擎都只认 [CidarenData.words] 这一份。
         */
        fun mergeWords(rich: List<CidarenWord>, cet4: List<CidarenWord>): List<CidarenWord> {
            val seen = HashSet<String>(rich.size + cet4.size)
            val out = ArrayList<CidarenWord>(rich.size + cet4.size)
            rich.forEach { val k = it.w.lowercase(); if (seen.add(k)) out.add(it) }
            cet4.forEach { val k = it.w.lowercase(); if (seen.add(k)) out.add(it) }
            return out
        }

        fun fromJson(text: String, cet4Json: String? = null): CidarenData {
            val json = JSONObject(text)
            val cet4 = cet4Json?.let { JSONArray(it).words() } ?: emptyList()
            val rich = json.optJSONArray("words").mapObjects(::word)
            return CidarenData(
                version = json.optString("version"),
                rich = rich,
                words = mergeWords(rich, cet4),
                cet4 = cet4,
                questions = json.optJSONArray("questions").mapObjects(::question),
                groups = json.optJSONObject("freeModels")?.optJSONArray("groups").mapObjects(::group) ?: emptyList(),
                models = json.optJSONObject("freeModels")?.optJSONArray("models").mapObjects(::model) ?: emptyList(),
            )
        }

        private fun word(o: JSONObject) = CidarenWord(
            w = o.optString("w"),
            ph = o.optString("ph"),
            pos = o.optString("pos"),
            cn = o.optString("cn"),
            level = o.optString("level"),
            tag = o.optJSONArray("tag").strings(),
            root = o.optJSONArray("root").mapObjects { CidarenRoot(it.optString("p"), it.optString("m")) },
            note = o.optString("note"),
            colloc = o.optJSONArray("colloc").strings(),
            ex = o.optJSONObject("ex")?.let { CidarenExample(it.optString("en"), it.optString("cn")) } ?: CidarenExample("", ""),
            confuse = o.optJSONArray("confuse").mapObjects { CidarenConfuse(it.optString("w"), it.optString("d")) },
        )

        private fun question(o: JSONObject) = CidarenQuestion(
            id = o.optString("id"),
            type = o.optString("type"),
            stem = o.optString("stem"),
            options = o.optJSONArray("options").strings(),
            answer = o.optInt("answer", -1),
            target = o.optString("target"),
            level = o.optString("level"),
            explain = o.optJSONObject("explain")?.let {
                CidarenExplain(
                    kp = it.optString("考点"),
                    sentence = it.optString("句意"),
                    items = it.optJSONArray("逐项").strings(),
                    extra = it.optString("拓展"),
                )
            } ?: CidarenExplain(),
        )

        private fun group(o: JSONObject) = CidarenModelGroup(o.optString("id"), o.optString("name"), o.optString("note"))

        private fun model(o: JSONObject) = CidarenFreeModel(
            id = o.optString("id"),
            group = o.optString("group"),
            name = o.optString("name"),
            url = o.optString("url"),
            model = o.optString("model"),
            noKey = o.optBoolean("noKey"),
            note = o.optString("note"),
            probe = o.optString("probe"),
            regUrl = o.optString("regUrl"),
        )
    }
}

/* ---------------- org.json 小工具 ---------------- */

private inline fun <T> JSONArray?.mapObjects(crossinline f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
    return out
}

private fun JSONArray?.words(): List<CidarenWord> {
    if (this == null) return emptyList()
    val out = ArrayList<CidarenWord>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { o ->
        out.add(
            CidarenWord(
                w = o.optString("w"),
                ph = o.optString("ph"),
                pos = o.optString("pos"),
                cn = o.optString("cn"),
                level = o.optString("level").ifEmpty { "四级" },
                note = o.optString("note"),
            )
        )
    }
    return out
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

/** 把词条写成 JSON（自定义词库的导出 / 持久化用）。 */
fun CidarenWord.toJson(): JSONObject = JSONObject().apply {
    put("w", w); put("ph", ph); put("pos", pos); put("cn", cn); put("level", level)
    put("tag", JSONArray(tag))
    put("root", JSONArray().apply { root.forEach { put(JSONObject().put("p", it.p).put("m", it.m)) } })
    put("note", note)
    put("colloc", JSONArray(colloc))
    put("ex", JSONObject().put("en", ex.en).put("cn", ex.cn))
    put("confuse", JSONArray().apply { confuse.forEach { put(JSONObject().put("w", it.w).put("d", it.d)) } })
}
