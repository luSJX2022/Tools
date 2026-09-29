package com.qzkt.timetable.jw

import com.qzkt.timetable.model.CourseSession
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 连接教务系统所需的配置。 */
data class JwConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/** 教务系统返回的学期信息。 */
data class JwTermInfo(
    /** 学年学期标识，如 `2026-2027-1`；取不到时为空串。 */
    val xnxqh: String,
    /** 当前教学周；取不到时为 null。 */
    val currentWeek: Int?,
    /** 第一周周一；取不到时为 null（界面会让用户手动设，或用 [currentWeek] 倒推）。 */
    val firstMonday: LocalDate?,
    /** 学期总周数；取不到时为 null，用设置页里的值。 */
    val weekCount: Int? = null,
    val raw: Map<String, String> = emptyMap(),
)

/** 一次 HTTP 往返的记录，给调试页看。 */
@Serializable
data class RawExchange(
    val at: Long = System.currentTimeMillis(),
    val label: String,
    val url: String,
    val responseSnippet: String,
    val ok: Boolean,
)

/** 教务接口调用失败。 */
class JwException(
    message: String,
    cause: Throwable? = null,
    /** 是否属于"网络连不上"这一类（可以用来判断要不要换个协议重试）。 */
    val connectivity: Boolean = false,
) : Exception(message, cause) {
    /**
     * 是不是被学校前面的网关挡下了（那层要浏览器执行 JS 才放行）。
     *
     * 这种情况换台设备、换个密码、换另一个接口都没用，只能改走"在应用内登录"。
     */
    var gateBlocked: Boolean = false

    /**
     * 是不是"这个接口在这个学校根本不存在"。
     *
     * 比如有的学校没开 app.do，请求它会返回一页 HTML。这种失败**值得换另一个适配器再试**，
     * 和"账号密码错"不一样 —— 后者换平台也白搭。
     */
    var wrongProtocol: Boolean = false

    /** 换另一个适配器/协议还有没有意义。 */
    val worthRetryingElsewhere: Boolean get() = connectivity || wrongProtocol
}

/**
 * 一个已登录的教务会话。
 *
 * 实现类负责持有 token / cookie，并在 [rawLog] 里留下每次请求的痕迹。
 */
interface JwSession {
    /** 学生姓名，取不到时为 null。 */
    val studentName: String?

    /** 每次请求的原始响应（最新在前）。 */
    val rawLog: List<RawExchange>

    /** 读取当前学期与当前周。 */
    suspend fun loadTerm(): JwTermInfo

    /** 读取指定周的课表。 */
    suspend fun loadWeek(week: Int): List<CourseSession>

    /**
     * 一次性返回整学期课表。
     *
     * 网页版接口是"一次拿到整学期"（每门课自带周次），不像 app.do 那样按周返回。
     * 按周返回的适配器保持默认的 null，由 [loadWeek] 负责。
     */
    suspend fun loadWholeTerm(): List<CourseSession>? = null

    /** 释放连接资源。 */
    fun close()
}

/** 教务适配器：负责登录并交出一个 [JwSession]。 */
interface JwAdapter {
    val id: String
    val displayName: String

    /** 登录并建立会话；失败抛 [JwException]。 */
    suspend fun connect(config: JwConfig): JwSession
}

/** 第一周周一无法从接口获得时，用「今天 + 当前周次」倒推。 */
internal fun deriveFirstMonday(today: LocalDate, currentWeek: Int): LocalDate =
    today.minusDays(((currentWeek - 1) * 7 + (today.dayOfWeek.value - 1)).toLong())
