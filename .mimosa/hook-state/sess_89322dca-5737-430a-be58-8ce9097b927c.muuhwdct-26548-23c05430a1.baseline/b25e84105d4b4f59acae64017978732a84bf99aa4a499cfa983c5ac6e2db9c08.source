package com.qzkt.timetable.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "qzkt_settings")

/** 读写 [AppSettings]。 */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        // 存储文件坏掉（写了一半、被外力改坏等）时 DataStore 会在读取阶段就抛异常，
        // 那个异常穿不过下面那句 runCatching，会让应用每次启动都崩。
        // 这里兜住它，退回默认设置 —— 用户最多是配置丢了，但应用还能用。
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[key]?.let { raw -> decode(raw) } ?: AppSettings()
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[key]?.let { decode(it) } ?: AppSettings()
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(existing))
        }
    }

    private fun decode(raw: String): AppSettings {
        val decoded = runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrNull()
            ?: return AppSettings()
        // 老版本里存的是通用作息，升级后换成学校真实的那份（改过的不会被动）
        return AppSettings.migrateSlots(decoded)
    }
}
