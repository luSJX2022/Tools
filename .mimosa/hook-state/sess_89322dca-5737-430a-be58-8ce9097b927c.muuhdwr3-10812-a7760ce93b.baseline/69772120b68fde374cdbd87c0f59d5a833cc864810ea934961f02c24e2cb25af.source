package com.qzkt.timetable.data

import com.qzkt.timetable.model.TimeSlot
import kotlinx.serialization.Serializable

/** 应用配置。整份以 JSON 存在 DataStore 的单个键里，避免几十个散键。 */
@Serializable
data class AppSettings(
    /** 学校强智教务系统地址，可填域名或完整 app.do 地址。 */
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** 是否改用 WebView 页面导入（app.do 接口不可用时）。 */
    val useWebImport: Boolean = false,
    /** 第一周周一，`yyyy-MM-dd`；空串表示还没设置。 */
    val firstMonday: String = "",
    /** 学期总周数。 */
    val weekCount: Int = 20,
    /** 每日节次时间表。 */
    val slots: List<TimeSlot> = defaultSlots(),
    /** 后台定时同步。 */
    val syncEnabled: Boolean = true,
    /** 同步间隔（分钟），最小 15。 */
    val syncIntervalMinutes: Int = 360,
    /** 上课前提醒。 */
    val remindEnabled: Boolean = true,
    val remindBeforeMinutes: Int = 15,
    /** `SYSTEM` / `LIGHT` / `DARK`。 */
    val themeMode: String = "SYSTEM",
    val dynamicColor: Boolean = true,
    /** 课表上是否显示非本周的课（灰显）。 */
    val showOtherWeeks: Boolean = true,
    /** 课表是否显示周六周日，关掉后只留周一到周五。 */
    val showWeekend: Boolean = true,
    /** 番剧一集放完是否自动接着放下一集。 */
    val autoPlayNext: Boolean = true,
    /**
     * 工具页（首页）要隐藏的入口 key：`timetable` / `book` / `resolve` / `anime`。
     *
     * 存「隐藏」而不是「显示」：列表缺省为空，老版本存的 JSON 没这个字段，
     * 反序列化出来自然等于全显示，不用做迁移。
     */
    val hiddenToolKeys: List<String> = emptyList(),
    /** 是否已完成首次配置。 */
    val configured: Boolean = false,
    /**
     * 在应用内 WebView 登录后拿到的会话 cookie。
     *
     * 有的学校（比如青岛农业大学海都学院）登录页有一道 JS 校验，
     * 纯 HTTP 客户端过不去，所以密码登录这条路走不通；
     * 但用户在应用内登录一次之后，这个 cookie 就能用来定时拉课表，自动同步照常工作。
     */
    val sessionCookie: String = "",
    val sessionSavedAt: Long = 0,
) {
    fun slotOf(period: Int): TimeSlot? = slots.firstOrNull { it.period == period }

    /** 会话已经存下来了（能支撑后台自动同步）。 */
    val hasSession: Boolean get() = sessionCookie.isNotBlank()

    companion object {
        /**
         * 默认作息：青岛农业大学海都学院教学时间表（11 节）。
         *
         * 用户学校给的就是这套，直接作为默认值；其他学校可以在设置页逐节改。
         */
        fun defaultSlots(): List<TimeSlot> = listOf(
            TimeSlot(1, "08:30", "09:15"),
            TimeSlot(2, "09:25", "10:10"),
            TimeSlot(3, "10:25", "11:10"),
            TimeSlot(4, "11:20", "12:05"),
            TimeSlot(5, "14:00", "14:45"),
            TimeSlot(6, "14:55", "15:40"),
            TimeSlot(7, "15:55", "16:40"),
            TimeSlot(8, "16:50", "17:35"),
            TimeSlot(9, "18:50", "19:35"),
            TimeSlot(10, "19:45", "20:30"),
            TimeSlot(11, "20:40", "21:25"),
        )

        /**
         * 把老版本留下的"通用 12 节作息"换成学校真实的作息。
         *
         * 只在用户从没动过它的时候换（当前值恰好等于旧默认值），
         * 手动改过节次时间的保持原样，不能把用户的设置冲掉。
         */
        fun migrateSlots(settings: AppSettings): AppSettings =
            if (settings.slots == LEGACY_DEFAULT_SLOTS) {
                settings.copy(slots = defaultSlots())
            } else {
                settings
            }

        /**
         * 上一版内置的通用作息（12 节）。
         *
         * 只用来做一次性迁移：老用户的设置里存着这一份，说明他没手动改过，
         * 那就换成学校真实的那份；改过的（不等于这一份）保持不动。
         */
        val LEGACY_DEFAULT_SLOTS: List<TimeSlot> = listOf(
            TimeSlot(1, "08:00", "08:45"),
            TimeSlot(2, "08:55", "09:40"),
            TimeSlot(3, "10:00", "10:45"),
            TimeSlot(4, "10:55", "11:40"),
            TimeSlot(5, "14:00", "14:45"),
            TimeSlot(6, "14:55", "15:40"),
            TimeSlot(7, "16:00", "16:45"),
            TimeSlot(8, "16:55", "17:40"),
            TimeSlot(9, "19:00", "19:45"),
            TimeSlot(10, "19:55", "20:40"),
            TimeSlot(11, "20:50", "21:35"),
            TimeSlot(12, "21:45", "22:30"),
        )
    }
}
