package com.qzkt.timetable.data

/**
 * 日志行的解析与筛选（纯函数，便于单测）。
 *
 * 行格式见 [AppLogStore.record]：yyyy-MM-dd HH:mm:ss.SSS 级别/插件名: 内容。
 * 多行内容（崩溃堆栈）会以「续行」形式跟在带 tag 的首行后面，续行本身没有 tag，
 * 所以按插件筛选时必须把续行一起带上，否则崩溃详情会被筛没。
 */
object LogFilters {

    private val TAG_RE = Regex("""\s(?:INFO|WARN|ERROR|DEBUG|TRACE|VERBOSE)/([^:]+):\s""")

    /** 一条完整日志：首行带 tag，后面的续行（堆栈等）一起算这一条。 */
    data class Entry(
        val level: String?,
        val tag: String?,
        val text: String,
    )

    private val LEVEL_RE = Regex("""\s(INFO|WARN|ERROR|DEBUG|TRACE|VERBOSE)/[^:]+:\s""")

    /** 取一行日志的插件名；不是插件格式（没有 级别/插件名:）就返回 null。 */
    fun pluginTagOf(line: String): String? =
        TAG_RE.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun levelOf(line: String): String? =
        LEVEL_RE.find(line)?.groupValues?.get(1)

    /** 把整份日志切成一条一条（带续行）。 */
    fun entries(lines: List<String>): List<Entry> {
        val result = ArrayList<Entry>()
        var currentTag: String? = null
        var currentLevel: String? = null
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) result.add(Entry(currentLevel, currentTag, buffer.toString().trimEnd()))
            buffer.clear()
        }
        for (line in lines) {
            val tag = pluginTagOf(line)
            if (tag != null) {
                flush()
                currentTag = tag
                currentLevel = levelOf(line)
            }
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
        flush()
        return result
    }

    /** 按插件筛选：plugin 为 null 时返回全部；返回的是扁平行（含续行）。 */
    fun filter(lines: List<String>, plugin: String?): List<String> {
        if (plugin == null) return lines
        val out = ArrayList<String>()
        var keep = false
        for (line in lines) {
            val tag = pluginTagOf(line)
            if (tag != null) keep = tag == plugin
            if (keep) out.add(line)
        }
        return out
    }

    /** 按插件筛选「整条日志」。 */
    fun filterEntries(lines: List<String>, plugin: String?): List<Entry> =
        entries(lines).filter { plugin == null || it.tag == plugin }

    /** 日志里出现过的插件名：先按已知清单顺序，再补清单外实际出现的 tag。 */
    fun tagsIn(lines: List<String>, known: List<String> = PluginLog.PLUGINS): List<String> {
        val present = LinkedHashSet(lines.mapNotNull(::pluginTagOf))
        val ordered = known.filter { it in present }
        val extra = present.filterNot { it in known }.sorted()
        return ordered + extra
    }
}
