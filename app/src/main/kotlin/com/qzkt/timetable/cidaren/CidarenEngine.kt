package com.qzkt.timetable.cidaren

import kotlin.math.max
import kotlin.math.min

/** 选项。 */
data class CidarenOption(val label: String, val text: String)

/** 逐项分析。 */
data class CidarenAnalysis(val label: String, val text: String, val correct: Boolean, val head: String, val body: String)

/** 解析结果里的一段（词根词缀 / 搭配 / 例句…）。 */
data class CidarenBlock(val title: String, val lines: List<String>)

/** 一次解析的完整结果。字段命名与网页版引擎一一对应，便于对照。 */
data class CidarenResult(
    val ok: Boolean = true,
    val type: String = "",
    val typeLabel: String = "",
    val source: String = "rules",
    val stem: String = "",
    val stemPlain: String = "",
    val options: List<CidarenOption> = emptyList(),
    val answerIndex: Int = -1,
    val answerText: String = "",
    val confidence: Double = 0.0,
    val confidenceLabel: String = "低",
    val entry: CidarenWord? = null,
    val kp: String = "",
    val structure: String = "",
    val analysis: List<CidarenAnalysis> = emptyList(),
    val blocks: List<CidarenBlock> = emptyList(),
    val warnings: List<String> = emptyList(),
    val bankId: String? = null,
    val error: String = "",
)

/**
 * 题目解析引擎 —— 网页版 js/engine.js 的 Kotlin 移植。
 *
 * 保持和网页版完全一致的判定顺序与文案：题库原题优先（直接用题库自带解析），
 * 否则按题型走规则推断（词性 / 搭配 / 例句语境 / 中文释义 / 选项排序）。
 * 移植是否忠实由 app/src/test 的引擎测试对着 fixtures.json 逐条比对。
 */
object CidarenEngine {

    /* ================= 基础工具 ================= */

    private val FW_RE = Regex("""[\uFF10-\uFF19\uFF21-\uFF3A\uFF41-\uFF5A\uFF08\uFF09\uFF0C\uFF0E\uFF1A\uFF1F\uFF01\uFF3B\uFF3D\u3002\u3000]""")
    private val FW_MAP = mapOf(
        '\uFF08' to "(", '\uFF09' to ")", '\uFF0C' to ",", '\uFF0E' to ".", '\uFF1A' to ":",
        '\uFF1F' to "?", '\uFF01' to "!", '\uFF3B' to "[", '\uFF3D' to "]", '\u3002' to ".", '\u3000' to " ",
    )

    /** 全角英数与部分全角标点转半角；中文顿号、分号保留原样。 */
    fun hw(s: String?): String = (s ?: "").replace(FW_RE) { m ->
        FW_MAP[m.value[0]] ?: (m.value[0].code - 0xFEE0).toChar().toString()
    }

    fun norm(s: String?): String = hw(s)
        .replace(Regex("""[\u2018\u2019]"""), "'")
        .replace(Regex("""[\u201C\u201D]"""), "\"")
        .replace(Regex("""[\u2013\u2014]"""), "-")
        .replace(Regex("""[ \t\r\u000C\u000B]+"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""\n{2,}"""), "\n")
        .trim()

    private fun lower(s: String?): String = norm(s).lowercase()

    private val WORD_RE = Regex("""[a-z][a-z'-]*""")

    fun tokens(s: String?): List<String> = WORD_RE.findAll(lower(s)).map { it.value }.toList()

    private fun contentTokens(s: String?): List<String> =
        tokens(s).filter { it.length > 2 && it !in CidarenAffixes.STOP }

    private val CJK_RE = Regex("""[\u4e00-\u9fa5]""")

    fun cjk(s: String?): String = CJK_RE.findAll(hw(s)).joinToString("") { it.value }

    private fun lev(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun sim(a: String?, b: String?): Double {
        val x = a ?: ""; val y = b ?: ""
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val len = max(x.length, y.length)
        if (len == 0) return 0.0
        return 1.0 - lev(x, y).toDouble() / len
    }

    private val BIGRAM_STRIP = Regex("""[^\u4e00-\u9fa5a-z0-9]""")

    private fun bigrams(s: String?): List<String> {
        val t = BIGRAM_STRIP.replace(s ?: "", "")
        if (t.length < 2) return if (t.isEmpty()) emptyList() else listOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }
    }

    private fun jac(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val setA = a.toHashSet(); val setB = b.toHashSet()
        val inter = setA.count { it in setB }
        val union = setA.size + setB.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** 中文释义相似度：字符二元组 Jaccard，互相包含时兜底 0.8。 */
    fun cnSim(a: String?, b: String?): Double {
        val x = a ?: ""; val y = b ?: ""
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var s = jac(bigrams(x), bigrams(y))
        if (x.contains(y) || y.contains(x)) s = max(s, 0.8)
        return s
    }

    private fun clamp(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))

    private fun confLabel(c: Double): String = if (c >= 0.8) "高" else if (c >= 0.6) "中" else "低"

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    private val TYPE_LABEL = mapOf(
        "cloze" to "选词填空", "cloze-naked" to "填空（无选项）", "choice-meaning" to "词义选择（英→中）",
        "choice-word" to "词义选择（中→英）", "match" to "词义匹配", "synonym" to "近义/形近辨析",
        "spelling" to "单词拼写", "antonym" to "反义词选择", "lookup" to "单词查询",
        "lookup-text" to "句子查询", "reverse" to "中文查词", "mixed" to "混合题型", "unknown" to "未识别",
    )

    /* ================= 词形还原 ================= */

    private val FORM_RULES = listOf(
        Regex("""ies$""") to "y", Regex("""ied$""") to "y", Regex("""ying$""") to "ie",
        Regex("""es$""") to "", Regex("""s$""") to "",
        Regex("""ing$""") to "", Regex("""ing$""") to "e",
        Regex("""ed$""") to "", Regex("""ed$""") to "e", Regex("""d$""") to "",
        Regex("""er$""") to "", Regex("""er$""") to "e", Regex("""est$""") to "", Regex("""est$""") to "e",
        Regex("""ly$""") to "", Regex("""ly$""") to "le",
    )

    fun forms(w: String): List<String> {
        val base = Regex("""[^a-z'-]""").replace(w.lowercase(), "")
        if (base.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(base)
        FORM_RULES.forEach { (re, rep) ->
            val b = re.replace(base, rep)
            if (b.length >= 3) out.add(b)
        }
        val dbl = Regex("""([b-df-hj-np-tv-z])\1(ed|ing|er|est)$""")
        dbl.find(base)?.let { out.add(dbl.replace(base, "$1")) }
        return out.toList()
    }

    /* ================= 数据与索引 ================= */

    private var data: CidarenData = CidarenData()
    private var extraWords: List<CidarenWord> = emptyList()

    private val wordIndex = HashMap<String, CidarenWord>()
    private class CnEntry(val word: CidarenWord, val cn: String, val bg: List<String>)
    private var cnIndex = listOf<CnEntry>()
    private class BankEntry(val q: CidarenQuestion, val nstem: String, val opts: List<String>, val target: String)
    private var bankIndex = listOf<BankEntry>()

    private val AUX = setOf(
        "be", "is", "are", "was", "were", "been", "being", "am", "do", "does", "did", "have", "has", "had",
        "will", "would", "shall", "should", "can", "could", "may", "might", "must", "to", "not", "the", "a", "an", "and", "or", "of", "in", "on", "at", "for", "with", "by",
    )

    /** 装载数据；[custom] 是用户导入的自定义词条，排在前面（同名优先用自定义的）。 */
    fun setData(source: CidarenData, custom: List<CidarenWord> = emptyList()) {
        data = source
        extraWords = custom.map { it.copy(custom = true) }
        wordIndex.clear(); cnIndex = emptyList(); bankIndex = emptyList()

        val words = extraWords + source.words
        val cn = ArrayList<CnEntry>()
        words.forEach { w ->
            val key = w.w.lowercase()
            if (key.isNotEmpty() && !wordIndex.containsKey(key)) wordIndex[key] = w
            val c = cjk(w.cn)
            cn.add(CnEntry(w, c, bigrams(c)))
        }
        cnIndex = cn
        bankIndex = source.questions.map { q ->
            BankEntry(q, bankStem(q.stem), q.options.map { it.lowercase() }, q.target.lowercase())
        }
    }

    fun words(): List<CidarenWord> = extraWords + data.words

    fun questions(): List<CidarenQuestion> = data.questions

    private fun bankStem(s: String): String =
        lower(s).replace(Regex("""\*+"""), "").replace(Regex("""_{2,}"""), " ___ ")
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5_ ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    fun lookup(w: String?): CidarenWord? {
        if (w.isNullOrBlank()) return null
        forms(w).forEach { f -> wordIndex[f]?.let { return it } }
        return null
    }

    /** 选项可能是短语（"was admitted"）：取其中最后一个实义词查词库。 */
    private fun lookupOption(text: String): CidarenWord? {
        lookup(text)?.let { return it }
        val ws = tokens(text)
        if (ws.size > 1) {
            for (i in ws.indices.reversed()) {
                if (ws[i] in AUX) continue
                lookup(ws[i])?.let { return it }
            }
        }
        return null
    }

    private fun reverseLookupByCn(text: String, limit: Int): List<Pair<CidarenWord, Double>> {
        val bg = bigrams(cjk(text))
        val q = cjk(text)
        val out = ArrayList<Pair<CidarenWord, Double>>()
        cnIndex.forEach { e ->
            if (e.cn.isEmpty()) return@forEach
            var s = jac(bg, e.bg)
            if (q.isNotEmpty() && (e.cn.contains(q) || q.contains(e.cn))) s = max(s, 0.85)
            if (s > 0.2) out.add(e.word to s)
        }
        return out.sortedByDescending { it.second }.take(limit)
    }

    /* ================= 题干解析 ================= */

    data class Option(val label: String, val text: String)

    class Parsed(
        val raw: String,
        val stem: String,
        val stemPlain: String,
        val options: List<Option>,
        val hasBlank: Boolean,
        val spellCn: String?,
        val spellFirst: String?,
        val spellLen: Int?,
        val enStem: List<String>,
        val cnStem: String,
    )

    private val OPT_MARK = Regex("""(^|[\s;；,，.。:：、)）(（\[【])([A-Ha-h])\s*[.、)）．:：]\s*""")
    private val BLANK_RE = Regex("""_{2,}|[（(]\s*[)）]|＿{2,}""")
    private val SPELL_RE = Regex("""^([\s\S]*?[\u4e00-\u9fa5])[^A-Za-z]{0,4}([A-Za-z])\s*(?:_{2,}|…+|\.{3,})\s*(?:([（(])?\s*(\d{1,2})\s*(?:个)?\s*(?:letters?|字母)?\s*([)）])?)?""")

    fun parse(text: String): Parsed {
        val raw = norm(text)
        val marks = ArrayList<Triple<Char, Int, Int>>() // letter, start, contentStart
        OPT_MARK.findAll(raw).forEach { m ->
            val letter = m.groupValues[2].uppercase()[0]
            val start = m.range.first + m.groupValues[1].length
            marks.add(Triple(letter, start, m.range.last + 1))
        }
        // 只保留从 A 开始连续的字母序列
        val seq = ArrayList<Triple<Char, Int, Int>>()
        var want = 'A'
        for (mk in marks) {
            if (mk.first == want) { seq.add(mk); want = (want.code + 1).toChar() }
            else if (mk.first == 'A' && seq.size >= 2) break
        }

        var stem = raw
        var options = emptyList<Option>()
        if (seq.size >= 2) {
            stem = raw.substring(0, seq[0].second)
            options = seq.mapIndexed { i, mk ->
                val end = if (i + 1 < seq.size) seq[i + 1].second else raw.length
                Option(mk.first.toString(), Regex("""[\s;；,，]+$""").replace(raw.substring(mk.third, end), "").trim())
            }
        } else {
            // 没有 A/B/C/D 标记：首行含空格、后面是几行孤立英文单词时视为选项
            val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size in 3..6) {
                val head = lines[0]
                val tail = lines.drop(1)
                val isWord = Regex("""^[a-zA-Z][a-zA-Z'\u2019-]*(\s+[a-zA-Z'\u2019-]+)?$""")
                if (BLANK_RE.containsMatchIn(head) && tail.all { isWord.matches(it) }) {
                    stem = head
                    options = tail.mapIndexed { i, t -> Option(('A'.code + i).toChar().toString(), t) }
                }
            }
        }

        stem = Regex("""^\s*(第?\s*\d+\s*[题.、)）]|Q\s*\d+[.、)）]?|[（(]\s*\d+\s*[)）])\s*""", RegexOption.IGNORE_CASE).replace(stem, "").trim()
        stem = Regex("""^[\s:：\-—.。]+|[\s:：\-—]+$""").replace(stem, "").trim()

        val hasBlank = BLANK_RE.containsMatchIn(stem)
        val stemPlain = BLANK_RE.replace(stem, " ___ ")

        var spellCn: String? = null; var spellFirst: String? = null; var spellLen: Int? = null
        SPELL_RE.find(stem)?.let { m ->
            spellCn = cjk(m.groupValues[1])
            spellFirst = m.groupValues[2].lowercase()
            spellLen = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
        }
        // 音标式的中文提示（"放弃；抛弃  a______ (7 letters)"）里，中文部分不含题号等噪音，直接用 cjk 结果
        return Parsed(raw, stem, stemPlain, options, hasBlank, spellCn, spellFirst, spellLen, tokens(stem), cjk(stem))
    }

    private fun isEnglishText(s: String) = Regex("""[a-z]""", RegexOption.IGNORE_CASE).containsMatchIn(s) && !CJK_RE.containsMatchIn(s)
    private fun isChineseText(s: String) = CJK_RE.containsMatchIn(s) && !Regex("""[a-z]{2,}""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    fun detectType(p: Parsed): String {
        val opts = p.options
        if (opts.isEmpty()) {
            if (p.spellFirst != null) return "spelling"
            if (isEnglishText(p.stem) && tokens(p.stem).size == 1) return "lookup"
            if (isChineseText(p.stem)) return "reverse"
            if (p.hasBlank) return "cloze-naked"
            if (isEnglishText(p.stem)) return "lookup-text"
            return "unknown"
        }
        val en = opts.count { isEnglishText(it.text) && !isChineseText(it.text) }
        val cn = opts.count { isChineseText(it.text) }
        if (cn >= opts.size * 0.75) {
            return if (tokens(p.stem).size == 1) "choice-meaning" else "match"
        }
        if (en >= opts.size * 0.75) {
            if (Regex("""(相反|反义|opposite)""", RegexOption.IGNORE_CASE).containsMatchIn(p.stem)) return "antonym"
            if (CJK_RE.containsMatchIn(p.stem) && Regex("""[A-Za-z]\s*_{2,}""").containsMatchIn(p.stem)) return "spelling"
            if (p.hasBlank) return "cloze"
            if (isChineseText(p.stem)) return "choice-word"
            return "synonym"
        }
        return "mixed"
    }

    /* ================= 词性推断 ================= */

    private val ADJ_LY = setOf(
        "friendly", "likely", "lively", "lovely", "lonely", "daily", "early", "weekly", "monthly", "yearly",
        "silly", "ugly", "holy", "costly", "deadly", "timely", "elderly", "orderly", "scholarly",
    )

    fun posOfWord(w: String, entry: CidarenWord?): String {
        entry?.pos?.takeIf { it.isNotBlank() }?.let { p ->
            val lp = p.lowercase()
            return when {
                Regex("""^(vt|vi|v)\b""").containsMatchIn(lp) -> "v"
                Regex("""^n\b""").containsMatchIn(lp) -> "n"
                Regex("""^adj""").containsMatchIn(lp) -> "adj"
                Regex("""^adv""").containsMatchIn(lp) -> "adv"
                else -> ""
            }
        }
        val lw = w.lowercase()
        if (lw.endsWith("ly") && lw !in ADJ_LY) return "adv"
        CidarenAffixes.POS_OF_SUFFIX.forEach { row ->
            if (Regex("(" + row[0] + ")$").containsMatchIn(lw)) return row[1]
        }
        return ""
    }

    class Slot(val pos: String, val hint: String)

    fun inferSlot(stem: String): Slot {
        val s = Regex("""\s+""").replace(stem, " ")
        CidarenAffixes.SLOT_RULES.forEach { r ->
            if (r.regex.containsMatchIn(s)) return Slot(r.pos, r.hint)
        }
        if (Regex("""^\s*_{2,}""").containsMatchIn(s)) return Slot("n", "空格位于句首，通常作主语，应填名词")
        return Slot("", "无法从句式直接判断词性，请结合语义与搭配判断")
    }

    /* ================= 词根词缀 ================= */

    private fun strip(x: String) = x.replace("-", "")

    private fun pickLongest(list: List<List<String>>, s: String, atEnd: Boolean): List<String>? {
        var best: List<String>? = null
        list.forEach { e ->
            val k = strip(e[0])
            if (k.isEmpty()) return@forEach
            val hit = if (atEnd) s.length > k.length && s.endsWith(k) else s.length > k.length && s.startsWith(k)
            if (hit && (best == null || k.length > strip(best!![0]).length)) best = e
        }
        return best
    }

    private fun pickRoot(s: String): List<String>? {
        var best: List<String>? = null
        CidarenAffixes.ROOT.forEach { e ->
            val k = strip(e[0])
            if (s.contains(k) && s.length - k.length <= 3 && (best == null || k.length > strip(best!![0]).length)) best = e
        }
        return best
    }

    /** 拆不出成分就返回 null（不编造词源）。 */
    fun decompose(word: String): String? {
        val w = Regex("""[^a-z]""").replace(word.lowercase(), "")
        if (w.length < 5) return null
        val parts = ArrayList<String>()
        var rest = w
        pickLongest(CidarenAffixes.PREFIX, rest, false)?.let { p ->
            parts.add(p[0] + "(" + p[1] + ")")
            rest = rest.substring(strip(p[0]).length)
        }
        val suf = pickLongest(CidarenAffixes.SUFFIX, rest, true)
        val core = if (suf != null) rest.substring(0, rest.length - strip(suf[0]).length) else rest
        pickRoot(core)?.let { r -> parts.add(r[0] + "(" + r[1] + ")") }
        if (suf != null) parts.add(suf[0] + "(" + suf[1] + ")")
        if (parts.size < 2) return null
        return parts.joinToString(" + ")
    }

    private fun rootInfo(entry: CidarenWord?): Pair<String, String>? {
        if (entry != null && entry.root.isNotEmpty()) {
            return entry.root.joinToString(" + ") { it.p + "(" + it.m + ")" } to "词库"
        }
        val d = decompose(entry?.w ?: "") ?: return null
        return d to "词根词缀自动拆分"
    }

    /* ================= 题库匹配 ================= */

    private class BankHit(val q: CidarenQuestion, val s: Double)

    private fun matchBank(p: Parsed): BankHit? {
        val nstem = bankStem(p.stem)
        val userOpts = p.options.map { it.text.lowercase() }
        var best: BankHit? = null
        bankIndex.forEach { b ->
            var s = if (b.nstem == nstem) 1.0 else sim(nstem, b.nstem)
            if (s < 0.86 && userOpts.isNotEmpty() && b.opts.isNotEmpty()) {
                val o = jac(userOpts, b.opts)
                if (o >= 0.75) s = max(s, 0.9)
            }
            if (tokens(nstem).size == 1 && nstem == b.nstem) s = 1.0
            if (s >= 0.86 && (best == null || s > best!!.s)) best = BankHit(b.q, s)
        }
        if (best != null) return best
        if (p.options.isNotEmpty() && tokens(p.stem).size == 1) {
            val tw = tokens(p.stem)[0].lowercase()
            bankIndex.forEach { b ->
                if (b.target == tw && jac(userOpts, b.opts) > 0.4) return BankHit(b.q, 0.8)
            }
        }
        return null
    }

    /* ================= 评分 ================= */

    private class Scored(
        val label: String,
        val text: String,
        val entry: CidarenWord?,
        var score: Double,
        val pos: String,
    ) {
        val reasons = ArrayList<Pair<Boolean, String>>() // ok to 说明
        val opt get() = Option(label, text)
    }

    private fun collocHit(stem: String, opt: String): String? {
        val entry = lookup(opt) ?: return null
        val sent = lower(stem)
        entry.colloc.forEach { raw ->
            val c = lower(raw).trim()
            if (c.length < 4) return@forEach
            if (sent.contains(c)) return raw
            val cw = tokens(c).firstOrNull()
            if (cw != null && cw != lower(opt) && sent.contains(cw)) return raw
        }
        return null
    }

    private fun exampleSim(stem: String, entry: CidarenWord?): Double {
        val en = entry?.ex?.en ?: ""
        if (en.isBlank()) return 0.0
        val a = contentTokens(stem.replace(Regex("""_{2,}"""), " "))
        val b = contentTokens(en)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return jac(a, b)
    }

    private class Ctx(
        val type: String,
        val slot: Slot,
        val stem: String,
        val cnStem: String,
        var answerEntry: CidarenWord? = null,
    )

    private fun scoreOption(label: String, text: String, ctx: Ctx): Scored {
        val entry = lookupOption(text)
        val sc = Scored(label, text, entry, 0.0, posOfWord(text, entry))
        if (entry != null) sc.score += 0.6
        if (ctx.slot.pos.isNotEmpty() && ctx.slot.pos != "same") {
            if (sc.pos == ctx.slot.pos) {
                sc.score += 3
                sc.reasons.add(true to "词性相符：" + (CidarenAffixes.POS_LABEL[ctx.slot.pos] ?: ctx.slot.pos) + "，符合句式要求")
            } else if (sc.pos.isNotEmpty() && sc.pos != ctx.slot.pos) {
                sc.score -= 3
                sc.reasons.add(false to "词性不符：此处需" + (CidarenAffixes.POS_LABEL[ctx.slot.pos] ?: ctx.slot.pos) + "，该项为" + (CidarenAffixes.POS_LABEL[sc.pos] ?: sc.pos))
            }
        }
        if (ctx.type == "cloze" || ctx.type == "synonym" || ctx.type == "mixed") {
            collocHit(ctx.stem, text)?.let { c ->
                sc.score += 4
                sc.reasons.add(true to "固定搭配证据：$c")
            }
            val es = exampleSim(ctx.stem, entry)
            if (es >= 0.25) {
                sc.score += 6 * es
                sc.reasons.add(true to "与词典例句高度接近（语境匹配 " + Math.round(es * 100) + "%）")
            }
            if (ctx.cnStem.isNotEmpty() && entry != null) {
                val cs = cnSim(ctx.cnStem, entry.cn)
                if (cs > 0.2) {
                    sc.score += 5 * cs
                    sc.reasons.add(true to "与题干中文提示吻合")
                }
            }
        }
        if (ctx.type == "choice-word" || ctx.type == "mixed") {
            if (entry != null) {
                val cs = cnSim(ctx.cnStem, entry.cn)
                sc.score += 6 * cs
                if (cs > 0.25) sc.reasons.add(true to "中文释义与题干相符")
            } else {
                sc.score -= 2
                sc.reasons.add(false to "本地词库未收录该词，无法比对释义")
            }
            if (ctx.slot.pos.isNotEmpty() && sc.pos.isNotEmpty() && sc.pos != ctx.slot.pos) sc.score -= 1
        }
        return sc
    }

    private fun entryLine(entry: CidarenWord?): String {
        if (entry == null) return "本地词库未收录"
        val t = if (entry.pos.isNotEmpty()) entry.pos + " " else ""
        return t + entry.cn
    }

    private fun analyzeOption(sc: Scored, ctx: Ctx, answerText: String): CidarenAnalysis {
        val entry = sc.entry
        val head = sc.label + ". " + sc.text + (if (entry != null) "　[" + entryLine(entry) + "]" else "　[词库未收录]")
        val correct = sc.text.lowercase() == answerText.lowercase()
        val body = if (correct) {
            "正确项。" + (if (entry != null) "词义为「" + entry.cn + "」" else "") +
                (if (ctx.slot.pos.isNotEmpty()) "，词性与句式要求一致（" + ctx.slot.hint + "）" else "") +
                (if (entry != null && entry.note.isNotEmpty()) "；" + entry.note else "") + "。"
        } else {
            val bits = ArrayList<String>()
            val posReason = sc.reasons.firstOrNull { !it.first }?.second
            val ans = ctx.answerEntry
            if (posReason != null) bits.add(posReason)
            else if (entry != null && ans != null && entry.cn.isNotEmpty()) {
                val s = cnSim(entry.cn, ans.cn)
                if (s > 0.3) bits.add("词义与正确项接近（" + entry.cn + " vs " + ans.cn + "），属于义近词干扰，需按语境界定")
                else bits.add("词义不符：意为「" + entry.cn + "」，与语境要求不符")
            } else if (entry == null) bits.add("词库未收录，需结合语境判断")
            if (entry != null && entry.pos.isNotEmpty() && ctx.slot.pos.isNotEmpty() && posOfWord(sc.text, entry) == ctx.slot.pos) {
                bits.add("词性虽然相符，但语义/搭配不成立")
            }
            "干扰项。" + bits.joinToString("；") + "。"
        }
        return CidarenAnalysis(sc.label, sc.text, correct, head, body)
    }

    private fun blocksFor(entry: CidarenWord?, extra: List<CidarenBlock> = emptyList()): List<CidarenBlock> {
        if (entry == null) return extra
        val out = ArrayList<CidarenBlock>()
        rootInfo(entry)?.let { out.add(CidarenBlock("词根词缀", listOf(it.first + "（" + it.second + "）"))) }
        if (entry.colloc.isNotEmpty()) out.add(CidarenBlock("常用搭配", entry.colloc))
        if (entry.ex.en.isNotEmpty()) out.add(CidarenBlock("例句", listOf(entry.ex.en + (if (entry.ex.cn.isNotEmpty()) "　" + entry.ex.cn else ""))))
        if (entry.confuse.isNotEmpty()) out.add(CidarenBlock("易混辨析", entry.confuse.map { it.w + "：" + it.d }))
        if (entry.tag.isNotEmpty()) out.add(CidarenBlock("标签", listOf(entry.level + " · " + entry.tag.joinToString(" · "))))
        return out + extra
    }

    private fun similarQuestions(entry: CidarenWord?, target: String?, limit: Int): List<CidarenQuestion> {
        val keys = ArrayList<String>()
        if (!target.isNullOrBlank()) keys.add(target.lowercase())
        entry?.let {
            keys.add(it.w.lowercase())
            it.confuse.forEach { c -> keys.add(c.w.lowercase()) }
        }
        val hits = ArrayList<Pair<CidarenQuestion, Int>>()
        data.questions.forEach { q ->
            var hit = 0
            if (q.target.isNotBlank() && keys.contains(q.target.lowercase())) hit += 3
            val stemL = q.stem.lowercase()
            keys.forEach { k -> if (k.isNotEmpty() && stemL.contains(k)) hit += 1 }
            if (hit > 0) hits.add(q to hit)
        }
        return hits.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /* ================= 结果构造 ================= */

    private class Res(val type: String, var stem: String, val stemPlain: String, options: List<Option>) {
        var ok = true
        var source = "rules"
        var typeLabel = TYPE_LABEL[type] ?: "词汇题"
        val options = options.toMutableList()
        var answerIndex = -1
        var answerText = ""
        var confidence = 0.0
        var confidenceLabel = "低"
        var entry: CidarenWord? = null
        var kp = ""
        var structure = ""
        val analysis = ArrayList<CidarenAnalysis>()
        val blocks = ArrayList<CidarenBlock>()
        val warnings = ArrayList<String>()
        var bankId: String? = null
        var error = ""

        fun toResult() = CidarenResult(
            ok = ok, type = type, typeLabel = typeLabel, source = source, stem = stem, stemPlain = stemPlain,
            options = options.map { CidarenOption(it.label, it.text) }, answerIndex = answerIndex, answerText = answerText, confidence = round2(confidence),
            confidenceLabel = confidenceLabel, entry = entry, kp = kp, structure = structure,
            analysis = analysis, blocks = blocks, warnings = warnings, bankId = bankId, error = error,
        )
    }

    private fun newRes(p: Parsed, type: String) = Res(type, p.stem, p.stemPlain, p.options)

    private fun finishFromBank(hit: BankHit, p: Parsed): Res {
        val q = hit.q
        val r = newRes(p, q.type)
        r.source = "bank"
        r.typeLabel = (TYPE_LABEL[q.type] ?: "题库题") + "（内置题库原题）"
        r.confidence = 0.98
        r.confidenceLabel = "高"
        if (tokens(p.stem).isNotEmpty()) r.stem = p.stem else r.stem = q.stem
        r.options.clear()
        q.options.forEachIndexed { i, t -> r.options.add(Option(('A'.code + i).toChar().toString(), t)) }
        r.answerIndex = q.answer
        r.answerText = q.options.getOrElse(q.answer) { "" }
        r.entry = lookup(q.target)
        r.kp = q.explain.kp
        r.structure = q.explain.sentence
        q.options.forEachIndexed { i, t ->
            val entry = lookup(t)
            val body = (q.explain.items.getOrElse(i) { "" }).replace(Regex("""^[A-H]\s*[.、)）]\s*"""), "")
            r.analysis.add(
                CidarenAnalysis(
                    ('A'.code + i).toChar().toString(), t, i == q.answer,
                    ('A'.code + i).toChar().toString() + ". " + t + (if (entry != null) "　[" + entryLine(entry) + "]" else ""),
                    body,
                )
            )
        }
        r.blocks.addAll(blocksFor(r.entry))
        if (q.explain.extra.isNotEmpty()) r.blocks.add(CidarenBlock("拓展", listOf(q.explain.extra)))
        r.bankId = q.id
        return r
    }

    /* ================= 主流程 ================= */

    fun analyze(text: String): CidarenResult {
        val raw = norm(text)
        val p = parse(raw)
        if (raw.isEmpty()) return CidarenResult(ok = false, error = "请输入题目内容", stem = "", stemPlain = "")
        matchBank(p)?.let { return finishFromBank(it, p).toResult() }
        return when (detectType(p)) {
            "lookup", "lookup-text" -> solveLookup(p)
            "reverse" -> solveReverse(p)
            "spelling" -> solveSpelling(p)
            "choice-meaning", "match" -> solveMeaning(p, detectType(p))
            "choice-word" -> solveWordChoice(p, detectType(p))
            "antonym" -> solveAntonym(p)
            "cloze", "synonym", "mixed", "cloze-naked" -> solveCloze(p, detectType(p))
            else -> solveLookup(p)
        }.toResult()
    }

    private fun solveLookup(p: Parsed): Res {
        val w = tokens(p.stem).firstOrNull() ?: ""
        val entry = lookup(w)
        val r = newRes(p, "lookup")
        r.typeLabel = "单词速查"
        if (entry == null) {
            r.ok = false
            r.warnings.add("本地词库未收录「" + w + "」，可在设置里导入自定义词库或换一道题。")
            decompose(w)?.let { r.blocks.add(CidarenBlock("词根词缀（自动拆分）", listOf(it))) }
            return r
        }
        r.entry = entry
        r.answerText = entry.w
        r.confidence = 0.99
        r.confidenceLabel = "高"
        r.structure = entry.pos + " " + entry.cn + (if (entry.ph.isNotEmpty()) "　/" + entry.ph + "/" else "")
        r.kp = entry.note
        r.blocks.addAll(blocksFor(entry))
        return r
    }

    private fun solveReverse(p: Parsed): Res {
        val r = newRes(p, "reverse")
        r.typeLabel = "中文查词"
        val cands = reverseLookupByCn(p.stem, 6)
        if (cands.isEmpty()) {
            r.ok = false
            r.warnings.add("词库中没有与「" + cjk(p.stem) + "」匹配的词条。")
            return r
        }
        r.entry = cands[0].first
        r.answerText = cands[0].first.w
        r.confidence = clamp(0.5 + cands[0].second, 0.4, 0.95)
        r.confidenceLabel = confLabel(r.confidence)
        r.structure = cands.joinToString("；") { it.first.w + " " + it.first.pos + " " + it.first.cn }
        r.kp = "按中文释义匹配，相似度 " + Math.round(cands[0].second * 100) + "%"
        r.blocks.addAll(blocksFor(cands[0].first))
        val alt = cands.drop(1).map { it.first.w + "　" + it.first.cn }
        if (alt.isNotEmpty()) r.blocks.add(CidarenBlock("其它候选", alt))
        return r
    }

    private fun solveSpelling(p: Parsed): Res {
        val r = newRes(p, "spelling")
        r.typeLabel = "单词拼写"
        val first = p.spellFirst
        val len = p.spellLen
        val cn = p.spellCn
        if (first == null || cn == null) {
            r.ok = false
            r.warnings.add("未能识别拼写题格式（示例：放弃；抛弃  a______ (8 letters)）")
            return r
        }
        if (p.options.isNotEmpty()) {
            val scored = p.options.map { o ->
                val t = Regex("""[^a-z]""").replace(o.text.lowercase(), "")
                val entry = lookup(t)
                var s = 0.0
                val why = ArrayList<String>()
                if (first.isEmpty() || t.startsWith(first)) s += 2 else why.add("首字母不是 $first")
                if (len != null) {
                    if (t.length == len) s += 2 else why.add("词长 " + t.length + "，题目提示 " + len + " 个字母")
                }
                if (entry != null) {
                    s += 2
                    val cs = cnSim(cn, entry.cn)
                    s += 4 * cs
                    if (cs > 0.25) why.add("词库释义「" + entry.cn + "」与中文提示吻合")
                    else why.add("词库释义「" + entry.cn + "」与中文提示不符")
                } else why.add("词库中查不到，属于形近拼错")
                SpellScored(o.label, o.text, entry, s, why)
            }.sortedByDescending { it.score }
            val top = scored[0]
            r.answerIndex = p.options.indexOfFirst { it.label == top.label }
            r.answerText = top.text
            r.entry = top.entry
            val margin = if (scored.size > 1) scored[0].score - scored[1].score else 1.0
            r.confidence = clamp(0.5 + margin * 0.08, 0.35, 0.95)
            r.confidenceLabel = confLabel(r.confidence)
            r.kp = "拼写题：" + cn + "，首字母 " + first + (if (len != null) "，共 $len 个字母" else "") + "。"
            r.structure = "用「中文释义 + 首字母 + 词长」三项同时核对，只有正确拼写能全部满足。"
            p.options.forEach { o ->
                val x = scored.first { it.label == o.label }
                val correct = o.label == top.label
                r.analysis.add(
                    CidarenAnalysis(
                        o.label, o.text, correct,
                        o.label + ". " + o.text + (if (x.entry != null) "　[" + entryLine(x.entry) + "]" else "　[词库未收录]"),
                        (if (correct) "正确拼写。" else "错误拼写。") + x.why.joinToString("；") + "。" +
                            (if (correct && x.entry != null && x.entry.note.isNotEmpty()) x.entry.note else ""),
                    )
                )
            }
            r.blocks.addAll(blocksFor(top.entry))
            return r
        }

        val cands = data.words.filter { w ->
            val full = w.w.lowercase()
            full.length >= 3 &&
                (first.isEmpty() || full.startsWith(first)) &&
                (len == null || full.length == len) &&
                cnSim(cn, w.cn) > 0.15
        }.map { it to cnSim(cn, it.cn) }.sortedByDescending { it.second }
        if (cands.isEmpty()) {
            r.ok = false
            r.warnings.add("词库中没有同时满足「首字母 $first」「长度 " + (len?.toString() ?: "?") + "」和释义的单词。")
            return r
        }
        r.entry = cands[0].first
        r.answerText = cands[0].first.w
        r.confidence = clamp(0.45 + cands[0].second, 0.4, 0.95)
        r.confidenceLabel = confLabel(r.confidence)
        r.kp = "拼写题：根据中文释义 + 首字母 + 词长定位单词，注意拼写中的双写字母与易错字母。"
        r.structure = "待填单词：" + cands[0].first.w + "（" + cands[0].first.pos + " " + cands[0].first.cn + "）"
        r.blocks.addAll(blocksFor(cands[0].first))
        val alt = cands.drop(1).take(4).map { it.first.w + "　" + it.first.cn }
        if (alt.isNotEmpty()) r.blocks.add(CidarenBlock("其它候选", alt))
        return r
    }

    private class SpellScored(
        val label: String,
        val text: String,
        val entry: CidarenWord?,
        val score: Double,
        val why: List<String>,
    )

    private fun solveMeaning(p: Parsed, type: String): Res {
        val r = newRes(p, type)
        val target = lookup(tokens(p.stem).firstOrNull() ?: "")
        if (target == null) {
            r.ok = false
            r.warnings.add("题干单词「" + (tokens(p.stem).firstOrNull() ?: "") + "」未收录在本地词库。")
            r.confidence = 0.2
            return r
        }
        r.entry = target
        val scores = p.options.map { o ->
            var s = cnSim(o.text, target.cn)
            val oCn = cjk(o.text)
            if (oCn.isNotEmpty() && (target.cn.contains(oCn) || oCn.contains(cjk(target.cn)))) s = max(s, 0.9)
            val posOk = target.pos.isNotEmpty() && o.text.lowercase().contains(target.pos.lowercase().replace(".", ""))
            Triple(o, s + if (posOk) 0.15 else 0.0, s)
        }
        val sorted = scores.sortedByDescending { it.second }
        val best = sorted[0]
        r.answerIndex = p.options.indexOfFirst { it.label == best.first.label }
        r.answerText = best.first.text
        val margin = if (sorted.size > 1) sorted[0].second - sorted[1].second else 0.5
        r.confidence = clamp(0.55 + margin * 0.6 + (if (best.second > 0.6) 0.1 else -0.1), 0.35, 0.96)
        r.confidenceLabel = confLabel(r.confidence)
        r.kp = "本题考查 " + target.w + " 的词义：" + target.pos + " " + target.cn + "。"
        r.structure = "题干为单词 " + target.w + (if (target.ph.isNotEmpty()) " /" + target.ph + "/" else "") + "，需选出与之对应的中文释义。"
        p.options.forEach { o ->
            val correct = o.label == best.first.label
            val rev = if (correct) null else reverseLookupByCn(o.text, 1).firstOrNull()
            val body = if (correct) {
                "正确项：与 " + target.w + " 的释义一致（" + target.cn + "）。"
            } else if (rev != null) {
                "干扰项：这是 " + rev.first.w + "（" + rev.first.cn + "）的释义，与 " + target.w + " 无关。"
            } else {
                "干扰项：与 " + target.w + " 的词义不符。"
            }
            r.analysis.add(CidarenAnalysis(o.label, o.text, correct, o.label + ". " + o.text, body))
        }
        r.blocks.addAll(blocksFor(target))
        return r
    }

    private fun solveWordChoice(p: Parsed, type: String): Res {
        val r = newRes(p, type)
        val ctx = Ctx("choice-word", Slot("", ""), p.stem, p.cnStem)
        val scored = p.options.map { scoreOption(it.label, it.text, ctx) }.sortedByDescending { it.score }
        val best = scored[0]
        r.answerIndex = p.options.indexOfFirst { it.label == best.label }
        r.answerText = best.text
        r.entry = best.entry
        val margin = if (scored.size > 1) scored[0].score - scored[1].score else 1.0
        r.confidence = clamp(0.5 + margin * 0.12 + (if (best.score > 3) 0.15 else 0.0), 0.35, 0.95)
        r.confidenceLabel = confLabel(r.confidence)
        r.kp = "中文释义选词：先按释义锁定候选，再用词性排除干扰项。"
        r.structure = "题干中文释义：" + p.cnStem
        scored.forEach { sc ->
            val correct = sc.label == best.label
            val body = if (correct) {
                "正确项：" + (if (sc.entry != null) sc.entry.pos + " " + sc.entry.cn + "，与题干释义相符" else "与题干释义相符") + "。"
            } else {
                "干扰项：" + (if (sc.entry != null) "词义为「" + sc.entry.cn + "」，与题干「" + p.cnStem + "」不符" else "词库未收录，无法比对") + "。"
            }
            r.analysis.add(
                CidarenAnalysis(
                    sc.label, sc.text, correct,
                    sc.label + ". " + sc.text + (if (sc.entry != null) "　[" + entryLine(sc.entry) + "]" else ""),
                    body,
                )
            )
        }
        r.blocks.addAll(blocksFor(best.entry))
        return r
    }

    private fun solveAntonym(p: Parsed): Res {
        val r = newRes(p, "antonym")
        r.typeLabel = "反义词选择"
        var target: CidarenWord? = null
        Regex("""\*\*([^*]+)\*\*""").find(p.stem)?.let { m ->
            target = lookup(tokens(m.groupValues[1]).firstOrNull() ?: "")
        }
        if (target == null) {
            val optSet = p.options.map { it.text.lowercase() }
            p.enStem.forEach { w ->
                if (target != null) return@forEach
                val e = lookup(w)
                if (e != null && e.cn.isNotEmpty() && e.w.lowercase() !in optSet) target = e
            }
        }
        val known = p.options.map { o ->
            val e = lookup(o.text)
            Triple(o, e, if (e != null && target != null) cnSim(e.cn, target!!.cn) else null)
        }
        val knownOnly = known.filter { it.third != null }.sortedBy { it.third }
        if (target == null || knownOnly.size < 2) {
            r.ok = false
            r.warnings.add("反义词题的判定依赖词库释义，本题未能可靠定位被比较的词，请对照下方释义自行判断。")
            r.structure = "反义词题：选择一个与题干中划线/加粗词意思相反的选项。"
            known.forEach { (o, e, _) ->
                r.analysis.add(
                    CidarenAnalysis(
                        o.label, o.text, false,
                        o.label + ". " + o.text + (if (e != null) "　[" + entryLine(e) + "]" else "　[词库未收录]"),
                        if (e != null) "词义：" + e.cn + "。" + e.note else "词库未收录，无法比对。",
                    )
                )
            }
            return r
        }
        val best = knownOnly[0]
        r.answerIndex = p.options.indexOfFirst { it.label == best.first.label }
        r.answerText = best.first.text
        r.entry = best.second
        val margin = if (knownOnly.size > 1) knownOnly[1].third!! - knownOnly[0].third!! else 0.1
        r.confidence = clamp(0.45 + margin * 0.5, 0.35, 0.8)
        r.confidenceLabel = confLabel(r.confidence)
        r.kp = "反义词题：被比较的词是 " + target!!.w + "（" + target!!.cn + "），应选释义与之相反的一项。"
        r.structure = "按中文释义差异排序：与 " + target!!.w + " 的释义差异最大的一项最可能是反义词。此判断为启发式，请务必核对释义。"
        known.forEach { (o, e, _) ->
            val correct = o.label == best.first.label
            r.analysis.add(
                CidarenAnalysis(
                    o.label, o.text, correct,
                    o.label + ". " + o.text + (if (e != null) "　[" + entryLine(e) + "]" else "　[词库未收录]"),
                    if (correct) "与 " + target!!.w + "（" + target!!.cn + "）释义差异最大，最符合「相反」的要求。" + (e?.note ?: "")
                    else if (e != null) "意思是「" + e.cn + "」，与 " + target!!.w + " 不是相反关系（可能反而相近）。" else "词库未收录，需自行判断。",
                )
            )
        }
        r.blocks.addAll(blocksFor(best.second))
        r.warnings.add("反义词题由规则启发式判断，置信度偏低，请核对释义。")
        return r
    }

    private fun solveCloze(p: Parsed, type: String): Res {
        val r = newRes(p, type)
        if (p.options.isEmpty()) {
            val cands = data.words.map { it to exampleSim(p.stemPlain, it) }
                .filter { it.second >= 0.3 }.sortedByDescending { it.second }
            r.typeLabel = "填空（无选项，自动推荐）"
            r.confidence = if (cands.isNotEmpty()) clamp(0.3 + cands[0].second, 0.3, 0.75) else 0.2
            r.confidenceLabel = confLabel(r.confidence)
            if (cands.isNotEmpty()) {
                r.answerText = cands[0].first.w
                r.entry = cands[0].first
                r.kp = "题目未给选项，按「词典例句语境匹配」推荐最可能的答案。"
                r.blocks.addAll(blocksFor(cands[0].first))
                val alt = cands.drop(1).take(4).map { it.first.w + "　" + it.first.cn }
                if (alt.isNotEmpty()) r.blocks.add(CidarenBlock("其它候选", alt))
            } else {
                r.ok = false
                r.warnings.add("没有选项也没有可匹配的例句，请补全选项。")
            }
            return r
        }
        val slot = inferSlot(p.stem)
        val ctx = Ctx(if (type == "mixed") "cloze" else type, slot, p.stemPlain, p.cnStem)
        val scored = p.options.map { scoreOption(it.label, it.text, ctx) }.sortedByDescending { it.score }
        val best = scored[0]
        ctx.answerEntry = best.entry
        r.answerIndex = p.options.indexOfFirst { it.label == best.label }
        r.answerText = best.text
        r.entry = best.entry
        val margin = if (scored.size > 1) scored[0].score - scored[1].score else 1.0
        val evidence = scored[0].reasons.size
        r.confidence = clamp(0.42 + min(margin, 6.0) * 0.07 + min(evidence.toDouble(), 3.0) * 0.05, 0.3, 0.95)
        r.confidenceLabel = confLabel(r.confidence)
        r.kp = "选词填空：" + slot.hint + "。" + (best.entry?.note ?: "")
        r.structure = "句式判断：" + slot.hint + "。句意需结合上下文确定，建议对照下方例句与搭配理解。"
        scored.forEach { sc -> r.analysis.add(analyzeOption(sc, ctx, best.text)) }
        r.blocks.addAll(blocksFor(best.entry))
        if (r.confidence < 0.6) r.warnings.add("本题未命中内置题库，答案为规则推断，置信度较低，建议人工核对。")
        return r
    }

    /* ================= 导出纯文本 ================= */

    fun toText(r: CidarenResult, withOptions: Boolean = true): String {
        if (!r.ok) return "【未能解析】" + r.warnings.joinToString("；")
        val sb = StringBuilder()
        sb.append("题型：").append(r.typeLabel).append('\n')
        sb.append("题干：").append(if (r.stemPlain.isNotEmpty()) r.stemPlain else r.stem).append('\n')
        if (withOptions && r.options.isNotEmpty()) r.options.forEach { sb.append("  ").append(it.label).append(". ").append(it.text).append('\n') }
        sb.append("答案：").append(if (r.answerIndex >= 0) ('A'.code + r.answerIndex).toChar() + ". " + r.answerText else r.answerText).append('\n')
        sb.append("置信度：").append(Math.round(r.confidence * 100)).append("%（").append(r.confidenceLabel).append("）\n")
        if (r.kp.isNotEmpty()) sb.append("考点：").append(r.kp).append('\n')
        if (r.structure.isNotEmpty()) sb.append("句意/结构：").append(r.structure).append('\n')
        if (r.analysis.isNotEmpty()) {
            sb.append("逐项分析：\n")
            r.analysis.forEach { sb.append("  ").append(it.head).append(" —— ").append(it.body).append('\n') }
        }
        r.blocks.forEach { b -> sb.append("【").append(b.title).append("】").append(b.lines.joinToString(" / ")).append('\n') }
        if (r.warnings.isNotEmpty()) sb.append("提示：").append(r.warnings.joinToString("；")).append('\n')
        return sb.toString().trimEnd()
    }
}
