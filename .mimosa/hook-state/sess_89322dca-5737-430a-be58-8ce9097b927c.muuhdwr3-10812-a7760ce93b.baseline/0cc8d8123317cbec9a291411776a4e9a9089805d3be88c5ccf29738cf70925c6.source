package com.qzkt.timetable.jw.parse

/**
 * 解析"节次"字段。
 *
 * 已知写法：
 * - `0102`（两位起 + 两位止，强智 app.do 最常见）
 * - `0304` / `0910` / `1112`
 * - `1-2`、`第1-2节`、`1~2`
 * - `1,2`
 * - `5`（单节）
 */
object PeriodParser {

    private const val MAX_PERIOD = 20

    /** 返回 1 起的闭区间，无法解析时返回 null。 */
    fun parse(raw: String): IntRange? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        if (text.contains('-') || text.contains(',')) {
            val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
            if (nums.isEmpty()) return null
            return clamp(nums.min(), nums.max())
        }

        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // 4 位及以上：两位起 + 两位止（0102 → 1..2，1112 → 11..12）
        if (digits.length >= 4 && digits.length % 2 == 0) {
            val chunks = digits.chunked(2).mapNotNull { it.toIntOrNull() }
            if (chunks.size >= 2) return clamp(chunks.first(), chunks.last())
        }

        val single = digits.toIntOrNull() ?: return null
        return clamp(single, single)
    }

    private fun clamp(start: Int, end: Int): IntRange? {
        if (start < 1 || start > MAX_PERIOD) return null
        return start..end.coerceIn(start, MAX_PERIOD)
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch.isDigit() || ch == '-' || ch == ',' -> sb.append(ch)
                ch == '~' || ch == '～' || ch == '－' || ch == '—' -> sb.append('-')
                else -> Unit // 丢掉"第""节"等噪声
            }
        }
        return sb.toString()
    }
}

/**
 * 解析星期：支持 `1`…`7`、`星期一`、`周一`、`星期天`。
 *
 * 只有在数字落在 1..7 时才接受数字写法，避免把"星期"串里的其它数字误判成星期几。
 */
object DayOfWeekParser {

    private val NAMES = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
    )

    fun parse(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        NAMES.forEach { (name, value) -> if (text.contains(name)) return value }

        val digit = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: return null
        return when {
            digit in 1..7 -> digit
            digit == 0 -> 7
            else -> null
        }
    }
}
