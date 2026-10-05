package com.qzkt.timetable.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.SettingsStore
import com.qzkt.timetable.data.TimetableRepository
import com.qzkt.timetable.model.CourseSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 下一节课的位置与时间。 */
data class UpcomingClass(
    val session: CourseSession,
    val startAt: LocalDateTime,
    val startTimeText: String,
)

/**
 * 上课前提醒。
 *
 * 同一时刻只挂一个闹钟：响过之后再排下一节。这样既不用维护一堆待定闹钟，
 * 也不会因为课表变动留下过期的提醒。
 */
class ClassReminderScheduler(
    private val context: Context,
    private val repository: TimetableRepository,
    private val settingsStore: SettingsStore,
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /** 重新安排下一节课的提醒；没有课或未开启提醒时取消已挂的闹钟。 */
    suspend fun rescheduleUpcoming() {
        val settings = settingsStore.current()
        if (!settings.remindEnabled) {
            cancel()
            return
        }

        val upcoming = nextClass(settings, LocalDateTime.now())
        if (upcoming == null) {
            cancel()
            return
        }

        val triggerAt = upcoming.startAt
            .minusMinutes(settings.remindBeforeMinutes.toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // 触发时间已经过了就直接跳过这一节（正常不会发生，兜底而已）
        if (triggerAt <= System.currentTimeMillis()) {
            cancel()
            return
        }

        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            putExtra(EXTRA_COURSE_NAME, upcoming.session.name)
            putExtra(EXTRA_ROOM, upcoming.session.room)
            putExtra(EXTRA_START_TIME, upcoming.startTimeText)
            putExtra(EXTRA_MINUTES, settings.remindBeforeMinutes)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val manager = alarmManager ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            // 用户没给"精确闹钟"权限时退化为近似提醒（可能偏差几分钟）
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel() {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ClassReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager?.cancel(pending)
    }

    /** 找出从现在起最近的一节课（最多往后找 8 天）。 */
    fun nextClass(settings: AppSettings, now: LocalDateTime): UpcomingClass? {
        for (offset in 0..8) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val week = repository.currentWeek(date)
            val sessions = repository.sessionsOn(week, date.dayOfWeek.value)

            val candidates = sessions.mapNotNull { session ->
                val slot = settings.slotOf(session.startPeriod) ?: return@mapNotNull null
                val start = runCatching { LocalTime.parse(slot.start) }.getOrNull() ?: return@mapNotNull null
                UpcomingClass(session, date.atTime(start), slot.start)
            }

            val next = candidates.filter { it.startAt.isAfter(now) }.minByOrNull { it.startAt }
            if (next != null) return next
        }
        return null
    }

    /** 今天及以后的课，按时间排序（给小组件和"下一节课"用）。 */
    fun upcomingForDate(date: LocalDate, settings: AppSettings, now: LocalDateTime): List<UpcomingClass> {
        val week = repository.currentWeek(date)
        return repository.sessionsOn(week, date.dayOfWeek.value)
            .mapNotNull { session ->
                val slot = settings.slotOf(session.startPeriod) ?: return@mapNotNull null
                val start = runCatching { LocalTime.parse(slot.start) }.getOrNull() ?: return@mapNotNull null
                UpcomingClass(session, date.atTime(start), slot.start)
            }
            .filter { it.startAt.isAfter(now) || it.startAt.toLocalDate().isAfter(now.toLocalDate()) }
            .sortedBy { it.startAt }
    }

    companion object {
        private const val REQUEST_CODE = 2001

        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_MINUTES = "minutes"
    }
}
