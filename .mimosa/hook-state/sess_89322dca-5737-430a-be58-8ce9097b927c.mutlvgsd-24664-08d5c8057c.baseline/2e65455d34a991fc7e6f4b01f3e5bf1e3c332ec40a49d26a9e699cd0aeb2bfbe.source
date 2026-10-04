package com.qzkt.timetable.ui.player

/**
 * LRC 歌词解析。
 *
 * 格式约定跟 cplayer 的 `lyrics.c` 保持一致（那套是在 Windows 端验证过的）：
 * - `[mm:ss.xx]` 时间标签，一行可以挂多个标签
 * - `[offset:毫秒]` 整体补偿，正数表示歌词往后移
 * - 增强型 LRC 的 `<mm:ss.xx>` 词级标签直接剥掉（我们只按行显示）
 * - `[ti:][ar:][al:][by:]` 等元数据标签忽略
 * - 小数位按位数判断：2 位是百分秒，3 位是毫秒，1 位是十分秒
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val WORD_TAG = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val OFFSET_TAG = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("\\[(?:ti|ar|al|by|re|ve|length):[^]]*]", RegexOption.IGNORE_CASE)

    /** 解析整份歌词；解析不出任何时间标签时返回空列表。 */
    fun parse(text: String): List<LrcLine> {
        if (text.isBlank()) return emptyList()

        // offset 可能出现在任意一行，先整体找出来
        val offsetMs = OFFSET_TAG.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lines = mutableListOf<LrcLine>()
        text.lineSequence().forEach { rawLine ->
            val line = META_TAG.replace(rawLine, "")
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            // 去掉所有时间标签和词级标签，剩下的就是这一句歌词
            val content = line
                .replace(TIME_TAG, "")
                .replace(WORD_TAG, "")
                .trim()

            tags.forEach { tag ->
                timeOf(tag)?.let { lines += LrcLine(it + offsetMs, content) }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 当前位置该显示第几行：返回最后一条 `timeMs <= positionMs` 的下标，
     * 还没到第一行时返回 -1（和 cplayer 的 `lyrics_index_at` 行为一致）。
     */
    fun lineAt(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun timeOf(tag: MatchResult): Long? {
        val minutes = tag.groupValues[1].toLongOrNull() ?: return null
        val seconds = tag.groupValues[2].toLongOrNull() ?: return null
        val fraction = tag.groupValues[3]

        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }
}
