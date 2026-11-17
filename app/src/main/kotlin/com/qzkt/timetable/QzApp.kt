package com.qzkt.timetable

import android.app.Application
import android.content.Context
import com.qzkt.timetable.data.SettingsStore
import com.qzkt.timetable.data.AppLogStore
import com.qzkt.timetable.data.PluginLog
import com.qzkt.timetable.data.TimetableRepository
import com.qzkt.timetable.data.TimetableStore
import com.qzkt.timetable.data.anime.AnimeStore
import com.qzkt.timetable.data.backup.BackupManager
import com.qzkt.timetable.data.book.BookStore
import com.qzkt.timetable.cidaren.CidarenStore
import com.qzkt.timetable.jw.JwAdapter
import com.qzkt.timetable.jw.qz.SmartQzAdapter
import com.qzkt.timetable.sync.ChangeNotifier
import com.qzkt.timetable.sync.ClassReminderScheduler
import com.qzkt.timetable.sync.SyncEngine
import com.qzkt.timetable.ui.player.link.ResolveHistoryStore
import java.io.File

/**
 * 手写的依赖容器。
 *
 * 这个应用的依赖图只有七八个对象，用 Hilt 反而要多引一个注解处理器和一堆构建配置，
 * 所以直接在这里 new 出来，按需传给 ViewModel / Worker / 小组件。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)

    val animeStore = AnimeStore(appContext)

    val bookStore = BookStore(appContext)

    val cidarenStore = CidarenStore(appContext)

    val resolveHistoryStore = ResolveHistoryStore(appContext)

    /** 设置 + 书架 + 追番的备份恢复（设置页「数据备份」用）。 */
    val backupManager = BackupManager(settingsStore, bookStore, animeStore)

    val repository = TimetableRepository(
        TimetableStore(File(appContext.filesDir, "timetable")),
    )

    val adapter: JwAdapter = SmartQzAdapter()

    val syncEngine = SyncEngine(settingsStore, repository, adapter)

    val notifier = ChangeNotifier(appContext)

    val classReminders = ClassReminderScheduler(appContext, repository, settingsStore)

    init {
        notifier.ensureChannels()
    }
}

class QzApp : Application() {

    /**
     * 懒初始化：WorkManager 的 ContentProvider 早于 `Application.onCreate` 执行，
     * 万一有 Worker 在那个时间点被调度到，也能安全拿到容器。
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = error.stackTraceToString()
                AppLogStore(this).record(
                    level = "ERROR",
                    tag = "Crash",
                    message = "未捕获异常，线程=${thread.name}\n${trace.take(16_000)}",
                )
            } catch (logError: Exception) {
                android.util.Log.e("AppLog", "记录崩溃日志失败", logError)
            }
            previousHandler?.uncaughtException(thread, error)
        }
        try {
            AppLogStore(this).record("INFO", "App", "应用进程启动")
            // 插件日志统一写进同一份 app.log（按插件名打 tag）
            PluginLog.install(this)
        } catch (logError: Exception) {
            android.util.Log.e("AppLog", "记录应用启动日志失败", logError)
        }
        // 主动创建一次，把通知渠道在进程启动时就注册好
        container
    }
}

/** 从任意 [Context] 取到依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as QzApp).container
