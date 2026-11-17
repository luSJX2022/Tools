package com.qzkt.timetable.cidaren

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义词库的导入解析 —— 网页版 js/ui.js 里 parseGlossary / normalizeWord 的 Kotlin 移植。
 *
 * 支持：
 *  - JSON：词条数组、{words:[...]}、{data:[...]}、["abandon"]；
 *    字段别名 w/word、ph/phonetic/ipa、pos、cn/meaning/trans/zh。
 *  - 文本：每行一条，用制表符 / 逗号 / 分号 / 竖线分隔，或「单词 释义」；
 *    「单词,音标,词性,释义」里的音标与词性会被识别出来（认不出就当释义）。
 */
object CidarenGlossary {

    private val WORD_RE = Regex("""^[A-Za-z][A-Za-z'’-]*$""")
    private val PHONETIC_RE = Regex("""[/\[]|ˈ|ˌ|ə|ɪ|ʊ|æ|ʌ|ɔ|ʒ|θ|ð|ŋ""")
    private val POS_RE = Regex("""^(n|v|vt|vi|adj|adv|prep|conj|pron|num|int)\.?$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<CidarenWord> {
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        if (s.startsWith("[") || s.startsWith("{")) return parseJson(s)
        val out = ArrayList<CidarenWord>()
        s.split(Regex("""\r?\n""")).forEach { line ->
            val ln = line.trim()
            if (ln.isEmpty() || ln.startsWith("#")) return@forEach
            out.addOrSkip(parseLine(ln))
        }
        return out
    }

    private fun parseJson(text: String): List<CidarenWord> {
        val root = try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: Exception) {
            return emptyList()
        }
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("words") ?: root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<CidarenWord>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is String) out.addOrSkip(normalize(CidarenWord(w = item.trim().lowercase())))
            else if (item is JSONObject) out.addOrSkip(
                normalize(
                    CidarenWord(
                        w = item.optString("w").ifEmpty { item.optString("word") },
                        ph = item.optString("ph").ifEmpty { item.optString("phonetic").ifEmpty { item.optString("ipa") } },
                        pos = item.optString("pos"),
                        cn = item.optString("cn").ifEmpty { item.optString("meaning").ifEmpty { item.optString("trans").ifEmpty { item.optString("zh") } } },
                        level = item.optString("level").ifEmpty { "自定义" },
                        note = item.optString("note"),
                    )
                )
            )
        }
        return out
    }

    private fun parseLine(ln: String): CidarenWord? {
        var parts: List<String> = when {
            ln.contains("\t") -> ln.split("\t")
            // 只认半角分号当分隔符：中文分号在词典里是"义项分隔"，不是字段分隔
            Regex("""[,，;|]""").containsMatchIn(ln) -> ln.split(Regex("""[,，;|]"""))
            else -> {
                val m = Regex("""^([A-Za-z][A-Za-z'’-]*)\s+(.+)$""").find(ln)
                if (m != null) listOf(m.groupValues[1], m.groupValues[2]) else listOf(ln)
            }
        }
        parts = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val head = parts.first()
        if (!WORD_RE.matches(head)) return null

        val rest = parts.drop(1).toMutableList()
        var ph = ""
        var pos = ""
        if (rest.size > 1 && PHONETIC_RE.containsMatchIn(rest[0])) ph = rest.removeAt(0)
        if (rest.size > 1 && POS_RE.matches(rest[0])) pos = rest.removeAt(0)
        val cn = rest.joinToString("，")
        return normalize(CidarenWord(w = head, ph = ph, pos = pos, cn = cn))
    }

    private fun normalize(w: CidarenWord): CidarenWord? {
        val word = w.w.trim().lowercase()
        if (!WORD_RE.matches(word)) return null
        return w.copy(w = word, level = w.level.ifEmpty { "自定义" }, custom = true)
    }

    private fun ArrayList<CidarenWord>.addOrSkip(w: CidarenWord?) {
        if (w != null) add(w)
    }

    /** 合并两份词条：同名单词以新导入的为准。 */
    fun merge(old: List<CidarenWord>, incoming: List<CidarenWord>): List<CidarenWord> {
        val map = LinkedHashMap<String, CidarenWord>()
        old.forEach { map[it.w] = it }
        incoming.forEach { map[it.w] = it }
        return map.values.sortedBy { it.w }
    }
}
