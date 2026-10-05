package com.qzkt.timetable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qzkt.timetable.appContainer
import com.qzkt.timetable.widget.TimetableWidgetUpdater

/** 后台定时同步。 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val settings = container.settingsStore.current()

        if (!settings.syncEnabled || !settings.configured || settings.baseUrl.isBlank()) {
            return Result.success()
        }

        // 和手动刷新共用同一套选路逻辑（SyncEngine.refresh）：有应用内登录的会话就用会话，
        // 没有才走账号密码接口。两边各写各的就会跑偏。
        val report = container.syncEngine.refresh()
        if (report == null) {
            // 没有可用的同步方式：安静跳过，免得每 6 小时推一条用户解决不了的"同步失败"
            TimetableWidgetUpdater.refresh(applicationContext)
            return Result.success()
        }

        if (report.success) {
            if (report.changes.isNotEmpty()) {
                container.notifier.notifyChanges(report.changes)
            }
            container.classReminders.rescheduleUpcoming()
        } else {
            container.notifier.notifySyncFailed(report.message)
        }

        TimetableWidgetUpdater.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        const val TAG = "qzkt-sync-worker"
    }
}
