package com.qzkt.timetable.data.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.expressDataStore: DataStore<Preferences> by preferencesDataStore(name = "parcel_express")

/**
 * 「我的快递 / 收件码」的本地状态。
 *
 * 为什么把 Cookie 存在本机：拼多多的这两个接口只认微信登录态，App 里没法走微信 OAuth，
 * 所以由用户从 Reqable 抓包里复制一次 Cookie 粘进来；它等价于一张会过期的门票。
 */
@Serializable
data class ExpressSavedState(
    val cookie: String = "",
    val overviewJson: String = "",
    val overviewAt: Long = 0L,
    val pickupJson: String = "",
    val pickupAt: Long = 0L,
    /** 头像 / 昵称的原始响应 + 时间。 */
    val profileJson: String = "",
    val profileAt: Long = 0L,
) {
    val hasCookie: Boolean get() = cookie.isNotBlank()
}

class ExpressStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val stateKey = stringPreferencesKey("express_state")

    val state: Flow<ExpressSavedState> = context.expressDataStore.data
        .map { preferences -> decode(preferences[stateKey]) }
        .catch { error ->
            if (error is IOException) emit(ExpressSavedState()) else throw error
        }

    suspend fun update(block: (ExpressSavedState) -> ExpressSavedState) {
        context.expressDataStore.edit { preferences ->
            val current = decode(preferences[stateKey])
            val next = block(current)
            preferences[stateKey] = json.encodeToString(ExpressSavedState.serializer(), next)
        }
    }

    suspend fun clear() {
        context.expressDataStore.edit { it.remove(stateKey) }
    }

    private fun decode(raw: String?): ExpressSavedState {
        if (raw.isNullOrBlank()) return ExpressSavedState()
        return runCatching { json.decodeFromString(ExpressSavedState.serializer(), raw) }
            .getOrDefault(ExpressSavedState())
    }
}

suspend fun clearExpressData(context: Context) {
    ExpressStore(context).clear()
}
