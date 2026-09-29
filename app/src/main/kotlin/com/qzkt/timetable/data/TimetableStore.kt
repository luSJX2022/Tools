package com.qzkt.timetable.data

import com.qzkt.timetable.jw.RawExchange
import com.qzkt.timetable.model.SyncChange
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 课表相关的本地持久化。
 *
 * 数据量很小（一学期几百条记录），整份存成 JSON 文件即可，不需要数据库；
 * 全部写入都走"写临时文件再改名"，避免中途被杀进程留下半个文件。
 */
class TimetableStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val snapshotFile = File(dir, "timetable.json")
    private val changeLogFile = File(dir, "changes.json")
    private val diagnosticsFile = File(dir, "diagnostics.json")
    private val rawPageFile = File(dir, "last-page.html")

    private val changeListSerializer = ListSerializer(SyncChange.serializer())
    private val exchangeListSerializer = ListSerializer(RawExchange.serializer())

    private companion object {
        const val MAX_CHANGES = 300
        const val MAX_EXCHANGES = 40
    }

    fun loadSnapshot(): TimetableSnapshot = read(snapshotFile, TimetableSnapshot.serializer()) ?: TimetableSnapshot()

    fun saveSnapshot(snapshot: TimetableSnapshot) = write(snapshotFile, TimetableSnapshot.serializer(), snapshot)

    /** 变更日志，最新在前。 */
    fun loadChanges(): List<SyncChange> = read(changeLogFile, changeListSerializer) ?: emptyList()

    fun appendChanges(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        val merged = (changes.reversed() + loadChanges()).take(MAX_CHANGES)
        write(changeLogFile, changeListSerializer, merged)
    }

    fun clearChanges() = write(changeLogFile, changeListSerializer, emptyList())

    /** 最近的接口往返记录，最新在前。 */
    fun loadDiagnostics(): List<RawExchange> = read(diagnosticsFile, exchangeListSerializer) ?: emptyList()

    /**
     * 把最近一次抓到的课表页原始 HTML 落盘。
     *
     * 各校强智的课表排版差异很大，解析不对时得能拿到真实页面下来照着改，
     * 而不是靠界面上那几行文字猜。
     */
    fun saveRawPage(html: String) {
        if (html.isBlank()) return
        runCatching { rawPageFile.writeText(html) }
    }

    fun loadRawPage(): String = runCatching { rawPageFile.readText() }.getOrDefault("")

    fun saveDiagnostics(exchanges: List<RawExchange>) =
        write(diagnosticsFile, exchangeListSerializer, exchanges.take(MAX_EXCHANGES))

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, value))
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
