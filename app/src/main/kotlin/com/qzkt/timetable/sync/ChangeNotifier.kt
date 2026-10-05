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

/** 发通知：上课提醒、同步失败。 */
class ChangeNotifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "课表同步", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "定时同步课表失败时提醒"
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
    fun notifySyncFailed(message: String) {
        if (!canNotify()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC)
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
        const val CHANNEL_SYNC = "qzkt_changes"
        const val CHANNEL_REMINDER = "qzkt_reminder"

        private const val NOTIFICATION_ID_REMINDER = 1002
        private const val NOTIFICATION_ID_SYNC_FAILED = 1003
    }
}
