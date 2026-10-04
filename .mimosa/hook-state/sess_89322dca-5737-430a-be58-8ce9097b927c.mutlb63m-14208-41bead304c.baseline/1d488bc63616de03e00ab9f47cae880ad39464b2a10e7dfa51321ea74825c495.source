package com.qzkt.timetable.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台定时同步的注册与取消。
 *
 * Android 对周期任务有 15 分钟的硬性下限，且系统在 Doze / 厂商省电策略下会推迟执行，
 * 所以"实时"在这里的含义是"按配置的间隔尽可能及时"，不是秒级。
 */
object SyncScheduler {

    private const val UNIQUE_PERIODIC = "qzkt-periodic-sync"

    /** Android 允许的最小周期。 */
    const val MIN_INTERVAL_MINUTES = 15

    fun schedule(context: Context, intervalMinutes: Int) {
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            // 间隔改了要立刻生效，所以用 UPDATE 而不是 KEEP
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }

    const val TAG = "qzkt-sync"
}
