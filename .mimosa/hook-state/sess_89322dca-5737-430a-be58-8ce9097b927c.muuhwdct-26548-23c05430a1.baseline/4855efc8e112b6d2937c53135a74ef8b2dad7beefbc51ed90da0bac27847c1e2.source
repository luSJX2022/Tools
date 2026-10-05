package com.qzkt.timetable.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.qzkt.timetable.MainActivity
import com.qzkt.timetable.R
import com.qzkt.timetable.model.SyncChange

/** 发通知：课表变动、上课提醒、同步失败。 */
class ChangeNotifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHANGES, "课表变动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "定时同步发现课程新增、停课或换教室时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上课前提前提醒"
            },
        )
    }

    /** 通知权限（Android 13+ 需要用户授权）。 */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun notifyChanges(changes: List<SyncChange>) {
        if (changes.isEmpty() || !canNotify()) return

        val title = if (changes.size == 1) "课表有更新" else "课表有 ${changes.size} 处更新"
        val text = changes.take(5).joinToString("\n") { change ->
            "${change.kind.label()} · 周${change.dayLabel()}第 ${change.periodSpan} 节 ${change.courseName}"
        } + if (changes.size > 5) "\n…还有 ${changes.size - 5} 处" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(changes.first().let { "${it.courseName} ${it.detail}" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CHANGES, notification)
    }

    @SuppressLint("MissingPermission")
    fun notifySyncFailed(message: String) {
        if (!canNotify()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课表同步失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_FAILED, notification)
    }

    /** 上课提醒。 */
    @SuppressLint("MissingPermission")
    fun notifyClassSoon(courseName: String, room: String, minutes: Int, startTime: String) {
        if (!canNotify()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$minutes 分钟后上 $courseName")
            .setContentText(listOfNotNull(startTime, room.ifBlank { null }).joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_REMINDER, notification)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_CHANGES = "qzkt_changes"
        const val CHANNEL_REMINDER = "qzkt_reminder"

        private const val NOTIFICATION_ID_CHANGES = 1001
        private const val NOTIFICATION_ID_REMINDER = 1002
        private const val NOTIFICATION_ID_SYNC_FAILED = 1003
    }
}

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun SyncChange.dayLabel(): String = DAY_NAMES.getOrElse(dayOfWeek - 1) { "?" }

internal fun SyncChange.Kind.label(): String = when (this) {
    SyncChange.Kind.ADDED -> "新增"
    SyncChange.Kind.REMOVED -> "停课"
    SyncChange.Kind.MODIFIED -> "变更"
    SyncChange.Kind.MOVED -> "调课"
}
