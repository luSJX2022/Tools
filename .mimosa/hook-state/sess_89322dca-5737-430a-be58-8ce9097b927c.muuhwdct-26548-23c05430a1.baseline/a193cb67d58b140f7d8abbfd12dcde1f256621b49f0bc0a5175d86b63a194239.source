package com.qzkt.timetable.jw.parse

import com.qzkt.timetable.model.CourseSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 解析结果。 */
data class KbcxParseResult(
    val sessions: List<CourseSession>,
    val warnings: List<String>,
    /** 原始返回项，调试页展示用。 */
    val rawItems: List<Map<String, String>>,
)

/**
 * 把强智 `getKbcxAzc` 的返回体解析成 [CourseSession]。
 *
 * 分两步：
 * 1. 按 [FieldAliases] 的别名表取字段；
 * 2. 别名表没命中的，用值的长相去猜（见 [guessMissing]）。
 *
 * 每所学校返回的键名都不一样，所以宁可先"猜"，猜不出来就留空并写进 warnings，
 * 同时原样保留原始数据 —— 换学校时照着调试页里的原始 JSON 往别名表里加一条即可，
 * 不需要改结构。
 */
object KbcxParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 周次解析不出来时的兜底周数，避免整门课在课表上直接消失。 */
    private const val DEFAULT_WEEK_COUNT = 20

    private val CHINESE_DAY = Regex("^星期[一二三四五六日天]$")
    private val PERSON_NAME = Regex("^[\\u4e00-\\u9fa5]{2,4}([,，、][\\u4e00-\\u9fa5]{2,4})*$")
    private val PURE_TEXT = Regex("^[\\u4e00-\\u9fa5A-Za-z（）()·\\-\\s]{2,30}$")
    private val CLASS_LIKE = Regex("^[\\u4e00-\\u9fa5]{2,8}\\d{2,4}$")
    private val ROOM_MARKERS = listOf('楼', '室', '馆', '区', '教', '厅')

    fun parseJson(text: String, termWeekCount: Int = DEFAULT_WEEK_COUNT): KbcxParseResult =
        parseItems(decodeItems(text), termWeekCount)

    /** 把返回体解成一批「字段名 → 字符串」的扁平行。 */
    fun decodeItems(text: String): List<Map<String, String>> {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { it.asFlatMap() }
            is JsonObject -> {
                // 有的学校把课表包在 {"data":[...]} / {"rows":[...]} 里
                val nested = element.values.firstOrNull { it is JsonArray } as? JsonArray
                nested?.mapNotNull { it.asFlatMap() } ?: listOfNotNull(element.asFlatMap())
            }

            else -> emptyList()
        }
    }

    fun parseItems(items: List<Map<String, String>>, termWeekCount: Int = DEFAULT_WEEK_COUNT): KbcxParseResult {
        val warnings = mutableListOf<String>()
        val sessions = items.mapNotNull { parseItem(it, termWeekCount, warnings) }
        return KbcxParseResult(sessions.distinctBy { it.id }, warnings, items)
    }

    private fun parseItem(
        item: Map<String, String>,
        termWeekCount: Int,
        warnings: MutableList<String>,
    ): CourseSession? {
        val lower = item.entries.associate { it.key.lowercase() to it.value }
        val consumed = mutableSetOf<String>()

        fun pick(aliases: List<String>): String? = aliases.firstNotNullOfOrNull { alias ->
            lower[alias]?.takeIf { it.isNotBlank() }?.also { consumed += alias }
        }

        var name = pick(FieldAliases.NAME).orEmpty()
        var teacher = pick(FieldAliases.TEACHER).orEmpty()
        var room = pick(FieldAliases.ROOM).orEmpty()
        var teachingClass = pick(FieldAliases.CLASS).orEmpty()

        val dayText = pick(FieldAliases.DAY_NUM) ?: pick(FieldAliases.DAY_NAME)
        val periodText = pick(FieldAliases.PERIODS)
        val weekText = pick(FieldAliases.WEEKS)
        val weekFlagText = FieldAliases.WEEK_FLAG.mapNotNull { lower[it] }.joinToString(" ")

        val guessed = guessMissing(
            lower = lower,
            consumed = consumed,
            needDay = dayText == null,
            needPeriods = periodText == null,
            needWeeks = weekText == null,
            needName = name.isEmpty(),
            needTeacher = teacher.isEmpty(),
            needRoom = room.isEmpty(),
            needClass = teachingClass.isEmpty(),
        )

        val day = dayText?.let { DayOfWeekParser.parse(it) } ?: guessed.day
        val periods = periodText?.let { PeriodParser.parse(it) } ?: guessed.periods
        val parsedWeeks = weekText?.let { WeekRangeParser.parse(it, weekFlagText) } ?: guessed.weekSpec
        if (name.isEmpty()) name = guessed.name
        if (teacher.isEmpty()) teacher = guessed.teacher.orEmpty()
        if (room.isEmpty()) room = guessed.room.orEmpty()
        if (teachingClass.isEmpty()) teachingClass = guessed.teachingClass.orEmpty()

        if (day == null || periods == null || name.isEmpty()) {
            warnings += "跳过一条无法识别的记录（课程名=\"$name\"，星期=$dayText，节次=$periodText）：$item"
            return null
        }

        val weeks = parsedWeeks?.weeks?.takeIf { it.isNotEmpty() }
            ?: (1..termWeekCount.coerceAtLeast(1)).toList().also {
                warnings += "「$name」周次识别不出，已按第 1-$termWeekCount 周处理（原始值：$weekText）"
            }

        return CourseSession(
            id = CourseSession.makeId(name.trim(), day, periods.first, periods.last, teachingClass.trim()),
            name = name.trim(),
            teacher = teacher.trim(),
            room = room.trim(),
            dayOfWeek = day,
            startPeriod = periods.first,
            endPeriod = periods.last,
            weeks = weeks.distinct().sorted(),
            teachingClass = teachingClass.trim(),
            raw = item,
        )
    }

    private data class Guess(
        val day: Int? = null,
        val periods: IntRange? = null,
        val weekSpec: WeekSpec? = null,
        val name: String = "",
        val teacher: String? = null,
        val room: String? = null,
        val teachingClass: String? = null,
    )

    /**
     * 别名表没命中的字段，按值的形态猜。
     *
     * 已认领的键名会立刻从候选里剔除，避免"节次"被下一步当成"周次"再认一遍。
     * 顺序固定为 星期 → 节次 → 周次 → 课程名 → 教师 → 教室 → 班级，后面的判断依赖前面的结果。
     */
    private fun guessMissing(
        lower: Map<String, String>,
        consumed: Set<String>,
        needDay: Boolean,
        needPeriods: Boolean,
        needWeeks: Boolean,
        needName: Boolean,
        needTeacher: Boolean,
        needRoom: Boolean,
        needClass: Boolean,
    ): Guess {
        val taken = consumed.toMutableSet()

        fun remaining(): List<Map.Entry<String, String>> =
            lower.entries.filter { it.key !in taken && it.value.isNotBlank() }

        var day: Int? = null
        var periods: IntRange? = null
        var weekSpec: WeekSpec? = null
        var name = ""
        var teacher: String? = null
        var room: String? = null
        var teachingClass: String? = null

        if (needDay) {
            remaining().firstOrNull { CHINESE_DAY.matches(it.value.trim()) }?.let { entry ->
                day = DayOfWeekParser.parse(entry.value)
                taken += entry.key
            }
        }

        if (needPeriods) {
            rankByHint(remaining(), FieldAliases.HEURISTIC_HINTS.getValue("period"))
                .firstOrNull { (key, value) ->
                    val parsed = PeriodParser.parse(value) ?: return@firstOrNull false
                    val keyLooksLikeWeek = key.contains("zc") || key.contains("week") || key.contains("周")
                    // 形如 "1,3,5-9" 或跨度超过 6 的，更像周次而不是节次
                    val valueLooksLikeWeeks = value.contains(',') ||
                        (value.contains('-') && parsed.last - parsed.first > 6)
                    !keyLooksLikeWeek && !valueLooksLikeWeeks
                }
                ?.let { entry ->
                    periods = PeriodParser.parse(entry.value)
                    taken += entry.key
                }
        }

        if (needWeeks) {
            rankByHint(remaining(), FieldAliases.HEURISTIC_HINTS.getValue("week"))
                .firstOrNull { (_, value) -> WeekRangeParser.parse(value).weeks.isNotEmpty() }
                ?.let { entry ->
                    weekSpec = WeekRangeParser.parse(entry.value)
                    taken += entry.key
                }
        }

        if (needName) {
            remaining()
                .filter { PURE_TEXT.matches(it.value.trim()) }
                .maxByOrNull { it.value.trim().length }
                ?.let { entry ->
                    name = entry.value.trim()
                    taken += entry.key
                }
        }

        if (needTeacher) {
            remaining().firstOrNull { PERSON_NAME.matches(it.value.trim()) }?.let { entry ->
                teacher = entry.value.trim()
                taken += entry.key
            }
        }

        if (needRoom) {
            remaining().firstOrNull { (_, value) ->
                val v = value.trim()
                v.length in 3..24 && ROOM_MARKERS.any { v.contains(it) }
            }?.let { entry ->
                room = entry.value.trim()
                taken += entry.key
            }
        }

        if (needClass) {
            remaining().firstOrNull { CLASS_LIKE.matches(it.value.trim()) }?.let { entry ->
                teachingClass = entry.value.trim()
            }
        }

        return Guess(day, periods, weekSpec, name, teacher, room, teachingClass)
    }

    /** 按键名里的提示词排序，命中的排前面；同分时保持原始顺序（sortedBy 是稳定排序）。 */
    private fun rankByHint(
        candidates: List<Map.Entry<String, String>>,
        hints: List<String>,
    ): List<Map.Entry<String, String>> = candidates.sortedBy { entry ->
        val key = entry.key.lowercase()
        hints.indexOfFirst { key.contains(it) }.let { if (it >= 0) it else hints.size }
    }

    private fun JsonElement.asFlatMap(): Map<String, String>? {
        val obj = this as? JsonObject ?: return null
        return obj.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
    }
}
