package com.qzkt.timetable.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qzkt.timetable.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 上课提醒闹钟到点：弹通知，然后立刻排下一节。 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val courseName = intent.getStringExtra(ClassReminderScheduler.EXTRA_COURSE_NAME) ?: return
        val room = intent.getStringExtra(ClassReminderScheduler.EXTRA_ROOM).orEmpty()
        val startTime = intent.getStringExtra(ClassReminderScheduler.EXTRA_START_TIME).orEmpty()
        val minutes = intent.getIntExtra(ClassReminderScheduler.EXTRA_MINUTES, 15)

        container.notifier.notifyClassSoon(courseName, room, minutes, startTime)

        // 广播有时间限制，用 goAsync 把"排下一节"的协程跑完
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.classReminders.rescheduleUpcoming()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** 开机 / 应用更新后重新排提醒和定时同步。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val container = context.appContainer
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = container.settingsStore.current()
                if (settings.configured) {
                    SyncScheduler.schedule(context.applicationContext, settings.syncIntervalMinutes)
                    container.classReminders.rescheduleUpcoming()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
