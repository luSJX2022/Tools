package com.qzkt.timetable.jw.parse

/** 单双周过滤条件。 */
enum class Parity { ALL, ODD, EVEN }

/** 一门课在哪些周上：周次列表 + 单双周限定。 */
data class WeekSpec(
    val weeks: List<Int>,
    val parity: Parity = Parity.ALL,
)

/**
 * 解析教务系统各种写法的周次串。
 *
 * 已知的真实写法（各校不同）：
 * - `1-16`
 * - `1,3,5-9`
 * - `1-16周`
 * - `1-16(单)` / `1-16（双）` / `第1-16周`
 * - `1、3、5、7`
 * - `１-１６`（全角）
 */
object WeekRangeParser {

    private const val MAX_WEEK = 40

    fun parse(raw: String, parityHint: String = ""): WeekSpec {
        val text = normalize(raw)
        val parity = detectParity(text + parityHint)

        if (text.isEmpty()) return WeekSpec(emptyList(), parity)

        val weeks = LinkedHashSet<Int>()
        text.split(',').forEach { token ->
            // 一个片段里可能还粘着"单/双/周"等字样（"1-8单"），只取数字，区间取首尾两个数
            val numbers = Regex("\\d+").findAll(token).mapNotNull { it.value.toIntOrNull() }.toList()
            when (numbers.size) {
                0 -> Unit
                1 -> if (numbers[0] in 1..MAX_WEEK) weeks += numbers[0]
                else -> {
                    val start = numbers.first()
                    val end = numbers.last()
                    if (start <= end) for (w in start..end) if (w in 1..MAX_WEEK) weeks += w
                }
            }
        }

        val sorted = weeks.sorted()
        val filtered = when (parity) {
            Parity.ALL -> sorted
            Parity.ODD -> sorted.filter { it % 2 == 1 }
            Parity.EVEN -> sorted.filter { it % 2 == 0 }
        }
        return WeekSpec(filtered, parity)
    }

    /** 从任意文本里识别单双周；同时出现"单"和"双"时认为无限制。 */
    fun detectParity(text: String): Parity {
        val hasOdd = text.contains('单')
        val hasEven = text.contains('双')
        return when {
            hasOdd && !hasEven -> Parity.ODD
            hasEven && !hasOdd -> Parity.EVEN
            else -> Parity.ALL
        }
    }

    /** 全角转半角、统一分隔符、去掉"第/周/()"等噪声。 */
    private fun normalize(raw: String): String {
        // 强智网页版的周次串长这样：`1-16(周)[0102]` —— 方括号里是节次，不是周次。
        // 必须先把方括号整段丢掉，否则 [0102] 的数字会粘到周次上（1-16 变成 1-160102）。
        val withoutBrackets = raw
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("【[^】]*】"), "")

        val sb = StringBuilder(withoutBrackets.length)
        for (ch in withoutBrackets) {
            val c = when (ch) {
                in '０'..'９' -> ('0' + (ch - '０'))
                '，', '、', '；', ';', '/' -> ','
                '－', '—', '–', '~', '～', '至' -> '-'
                '（', '）', '(', ')', '[', ']', '【', '】' -> ' '
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString()
            .replace("第", "")
            .replace("周", "")
            .replace(Regex("\\s+"), "")
    }
}
