package com.qzkt.timetable.data.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

private val Context.parcelDataStore: DataStore<Preferences> by preferencesDataStore(name = "parcel_pickup")

@Serializable
data class SavedParcel(
    val id: String,
    val name: String,
    val trackingNumber: String,
    val carrier: String,
    val addedAt: Long,
)

@Serializable
private data class ParcelStoreData(val parcels: List<SavedParcel> = emptyList())

data class ParcelDraft(
    val name: String,
    val trackingNumber: String,
    val carrier: String,
)

data class ParcelListState(
    val parcels: List<SavedParcel> = emptyList(),
    val error: String? = null,
)

internal fun validateParcelDraft(name: String, trackingNumber: String, carrier: String): ParcelDraft {
    val tracking = trackingNumber.trim()
    require(tracking.length in 4..50) { "运单号长度应为 4–50 个字符" }
    return ParcelDraft(
        name = name.trim().ifBlank { "我的包裹" }.take(40),
        trackingNumber = tracking,
        carrier = carrier.trim().take(40),
    )
}

/* ---------------- 抓包导入 ---------------- */

/**
 * 抓包里的一条包裹（拼多多「取快递」页面 package_out_list 的一项）。
 * 字段名沿用页面数据里的意思：address→站点/取件点，shipping_name→快递公司。
 */
@Serializable
data class CapturedParcel(
    val name: String = "",
    val trackingNumber: String = "",
    val carrier: String = "",
    val operateTime: String = "",
)

/** 一次抓包的完整内容：来源地址、抓包时间、站点与包裹列表。 */
@Serializable
data class ParcelCapture(
    val sourceUrl: String = "",
    val capturedAt: String = "",
    val station: String = "",
    val hint: String = "",
    val parcels: List<CapturedParcel> = emptyList(),
)

/** 导入结果：新增几条、因运单号已存在而跳过几条。 */
data class ParcelImportResult(val added: Int, val skipped: Int) {
    val summary: String
        get() = when {
            added > 0 && skipped > 0 -> "已导入 $added 个包裹，跳过 $skipped 个已存在的运单号"
            added > 0 -> "已导入 $added 个包裹"
            skipped > 0 -> "没有新包裹：$skipped 个运单号已在列表里"
            else -> "抓包数据里没有可导入的包裹"
        }
}

private val captureJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 解析抓包 JSON；格式不对时抛 IllegalArgumentException 并带人话原因。 */
fun parseParcelCapture(raw: String): ParcelCapture {
    val text = raw.trim()
    require(text.isNotEmpty()) { "抓包数据是空的" }
    return try {
        captureJson.decodeFromString(ParcelCapture.serializer(), text)
    } catch (error: SerializationException) {
        throw IllegalArgumentException("抓包数据格式不对：" + (error.message ?: "解析失败"), error)
    }
}

/** 抓包内容 → 可入库的草稿；运单号不合法（空/过短/过长）的条目直接丢弃。 */
fun capturedToDrafts(capture: ParcelCapture): List<ParcelDraft> =
    capture.parcels.mapNotNull { parcel ->
        val tracking = parcel.trackingNumber.trim()
        if (tracking.isEmpty()) return@mapNotNull null
        val name = parcel.name.trim().ifBlank { capture.station.trim() }.ifBlank { "我的包裹" }
        runCatching { validateParcelDraft(name, tracking, parcel.carrier) }.getOrNull()
    }

class ParcelStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val parcelsKey = stringPreferencesKey("parcels_json")

    val parcelState: Flow<ParcelListState> = context.parcelDataStore.data
        .map { preferences ->
            ParcelListState(decode(preferences[parcelsKey]).parcels.sortedByDescending(SavedParcel::addedAt))
        }
        .catch { error ->
            when (error) {
                is IOException -> emit(ParcelListState(error = "读取本地包裹失败：${error.message ?: "存储不可用"}"))
                is IllegalStateException -> emit(ParcelListState(error = error.message ?: "本地包裹数据无法读取"))
                else -> throw error
            }
        }

    suspend fun add(draft: ParcelDraft) {
        context.parcelDataStore.edit { preferences ->
            val current = decode(preferences[parcelsKey])
            require(current.parcels.none { it.trackingNumber.equals(draft.trackingNumber, ignoreCase = true) }) {
                "这个运单号已经添加"
            }
            val parcel = SavedParcel(
                id = UUID.randomUUID().toString(),
                name = draft.name,
                trackingNumber = draft.trackingNumber,
                carrier = draft.carrier,
                addedAt = System.currentTimeMillis(),
            )
            preferences[parcelsKey] = json.encodeToString(
                ParcelStoreData.serializer(),
                current.copy(parcels = current.parcels + parcel),
            )
        }
    }

    /**
     * 导入一次抓包：运单号已存在就跳过（不覆盖用户自己的备注），
     * 返回新增/跳过条数，界面据此提示。
     */
    suspend fun importCaptured(capture: ParcelCapture): ParcelImportResult {
        val drafts = capturedToDrafts(capture)
        if (drafts.isEmpty()) return ParcelImportResult(0, 0)
        var added = 0
        var skipped = 0
        context.parcelDataStore.edit { preferences ->
            val current = decode(preferences[parcelsKey])
            val seen = current.parcels.map { it.trackingNumber.lowercase() }.toMutableSet()
            val now = System.currentTimeMillis()
            val fresh = ArrayList<SavedParcel>()
            drafts.forEachIndexed { index, draft ->
                if (!seen.add(draft.trackingNumber.lowercase())) {
                    skipped++
                } else {
                    fresh += SavedParcel(
                        id = UUID.randomUUID().toString(),
                        name = draft.name,
                        trackingNumber = draft.trackingNumber,
                        carrier = draft.carrier,
                        // 同一批导入的按顺序排开，列表里保持抓包顺序
                        addedAt = now + index,
                    )
                    added++
                }
            }
            if (fresh.isEmpty()) return@edit
            preferences[parcelsKey] = json.encodeToString(
                ParcelStoreData.serializer(),
                current.copy(parcels = current.parcels + fresh),
            )
        }
        return ParcelImportResult(added, skipped)
    }

    suspend fun remove(id: String) {
        context.parcelDataStore.edit { preferences ->
            val current = decode(preferences[parcelsKey])
            preferences[parcelsKey] = json.encodeToString(
                ParcelStoreData.serializer(),
                current.copy(parcels = current.parcels.filterNot { it.id == id }),
            )
        }
    }

    suspend fun clear() {
        context.parcelDataStore.edit { it.remove(parcelsKey) }
    }

    private fun decode(raw: String?): ParcelStoreData {
        if (raw == null) return ParcelStoreData()
        return try {
            json.decodeFromString(ParcelStoreData.serializer(), raw)
        } catch (error: SerializationException) {
            throw IllegalStateException("本地包裹数据损坏，请在插件管理中清除该插件数据后重试", error)
        }
    }
}

suspend fun clearParcelPickupData(context: Context) {
    // 除了运单与网页 Cookie，还要清掉拼多多登录态（那是可复用的凭据，必须能一键抹掉）
    clearExpressData(context)
    ParcelStore(context).clear()
    clearParcelPickupWebData()
}
