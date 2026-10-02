package com.qzkt.timetable

import android.app.Application
import android.content.Context
import com.qzkt.timetable.data.SettingsStore
import com.qzkt.timetable.data.TimetableRepository
import com.qzkt.timetable.data.TimetableStore
import com.qzkt.timetable.data.anime.AnimeStore
import com.qzkt.timetable.data.book.BookStore
import com.qzkt.timetable.jw.JwAdapter
import com.qzkt.timetable.jw.qz.SmartQzAdapter
import com.qzkt.timetable.sync.ChangeNotifier
import com.qzkt.timetable.sync.ClassReminderScheduler
import com.qzkt.timetable.sync.SyncEngine
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
        // 主动创建一次，把通知渠道在进程启动时就注册好
        container
    }
}

/** 从任意 [Context] 取到依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as QzApp).container
