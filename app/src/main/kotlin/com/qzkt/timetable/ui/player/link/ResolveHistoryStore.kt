package com.qzkt.timetable.ui.player.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 链接解析历史：最近解析过的分享链接记一份（平台 / 标题 / 用户当时粘贴的原文），
 * 链接解析页输入为空时列出来，点一下直接重新解析，不用再去翻聊天记录复制。
 *
 * 存在 SharedPreferences 的一个键里（JSON 数组，最新在前），最多留 [MAX_ENTRIES] 条；
 * 同一条链接重复解析只更新时间，不刷屏。
 */
class ResolveHistoryStore(context: Context) {

    data class Entry(
        val platform: String,
        val title: String?,
        /** 用户粘贴的原文（分享文案或地址），重新解析时原样喂回去。 */
        val input: String,
        val at: Long,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun entries(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, "[]") ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            Entry(
                platform = obj.optString("platform"),
                title = obj.optString("title").ifBlank { null },
                input = obj.optString("input"),
                at = obj.optLong("at"),
            )
        }.filter { it.input.isNotBlank() }
    }

    fun add(platform: String, title: String?, input: String) {
        val rest = entries().filterNot { it.input == input }
        val updated = listOf(Entry(platform, title, input, System.currentTimeMillis())) + rest
        prefs.edit().putString(KEY_ENTRIES, JSONArray().apply {
            updated.take(MAX_ENTRIES).forEach { entry ->
                put(JSONObject().apply {
                    put("platform", entry.platform)
                    put("title", entry.title ?: "")
                    put("input", entry.input)
                    put("at", entry.at)
                })
            }
        }.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY_ENTRIES).apply()

    private companion object {
        const val PREFS_NAME = "resolve_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 20
    }
}
