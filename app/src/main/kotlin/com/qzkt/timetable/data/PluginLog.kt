package com.qzkt.timetable.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 插件日志的统一入口。
 *
 * 所有插件（教务、图书、链接解析、影视、四级词库、易校园、菜鸟包裹、P2P 密钥验证…）
 * 都通过这里写进 [AppLogStore] 的那一份 logs/app.log，
 * 日志行格式是「时间 级别/插件名: 内容」，所以设置里的「日志」页既能看全部，也能按插件名筛。
 *
 * 写文件是 IO：这里放到后台协程里做，调用方（包括 UI 线程）不需要关心阻塞。
 */
object PluginLog {

    /** 与工具列表一致；设置里的日志页用这份清单生成筛选 chip。 */
    val PLUGINS = listOf(
        "教务",
        "课表",
        "图书",
        "链接解析",
        "影视",
        "四级词库",
        "易校园",
        "菜鸟包裹",
        "P2P 密钥验证",
        "设置",
        "备份",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: AppLogStore? = null

    /** 在 Application.onCreate 里调一次。 */
    fun install(context: Context) {
        store = AppLogStore(context.applicationContext)
    }

    fun i(plugin: String, message: String) = write("INFO", plugin, message)

    fun w(plugin: String, message: String, error: Throwable? = null) = write(
        "WARN",
        plugin,
        if (error == null) message else message + "｜" + (error.message ?: error.javaClass.simpleName),
    )

    fun e(plugin: String, message: String, error: Throwable? = null) = write(
        "ERROR",
        plugin,
        if (error == null) message else message + "｜" + (error.message ?: error.javaClass.simpleName),
    )

    private fun write(level: String, plugin: String, message: String) {
        val target = store ?: return
        val tag = plugin.trim().takeIf { it.isNotEmpty() } ?: "插件"
        val text = message.replace(Regex("""[\r\n]+"""), " ").take(4_000)
        scope.launch {
            runCatching { target.record(level, tag, text) }
        }
    }
}
