package com.qzkt.timetable.yxy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val Context.electricityHistoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "yxy_electricity_history")

private val electricityDataKey = stringPreferencesKey("electricity_data")

@Serializable
internal data class ElectricityBalanceSnapshot(
    val roomKey: String,
    val mdtype: String,
    val balanceKwh: Double,
    val recordedAt: Long,
)

@Serializable
internal data class ElectricityMeterLabel(
    val roomKey: String,
    val mdtype: String,
    val label: String,
)

@Serializable
private data class ElectricityHistoryData(
    val snapshots: List<ElectricityBalanceSnapshot> = emptyList(),
    val meterLabels: List<ElectricityMeterLabel> = emptyList(),
)

internal data class ElectricityEstimate(
    val averageDailyKwh: Double,
    val remainingDays: Int?,
)

internal fun createManualElectricityRoom(
    areaId: String,
    buildingCode: String,
    floorCode: String,
    roomCode: String,
    displayName: String,
): YxyElectricityRoom {
    val values = listOf(areaId.trim(), buildingCode.trim(), floorCode.trim(), roomCode.trim())
    require(values.all { it.isNotBlank() && it.length <= 64 }) {
        "区域、楼栋、楼层和房间 ID 均为必填项（每项不超过 64 个字符）"
    }
    return YxyElectricityRoom(
        areaId = values[0],
        buildingCode = values[1],
        floorCode = values[2],
        roomCode = values[3],
        displayName = displayName.trim().ifBlank { values.joinToString("-") }.take(80),
    )
}

internal fun estimateElectricityDays(
    remainingKwh: Double?,
    records: List<YxyUsageRecord>,
    today: LocalDate = LocalDate.now(),
): ElectricityEstimate? {
    val remaining = remainingKwh?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val rangeStart = today.minusDays(7)
    val dailyUsage = records.mapNotNull { record ->
        val date = record.datetime.toLocalDateOrNull() ?: return@mapNotNull null
        val usage = record.usage.toFiniteDoubleOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        date to usage
    }.filter { (date, _) -> date >= rangeStart && date < today }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }

    if ((0L..6L).any { rangeStart.plusDays(it) !in dailyUsage }) return null
    val average = (0L..6L).sumOf { dailyUsage.getValue(rangeStart.plusDays(it)) } / 7.0
    val remainingDays = if (average > 0) kotlin.math.ceil(remaining / average).toInt() else null
    return ElectricityEstimate(average, remainingDays)
}

private fun String.toLocalDateOrNull(): LocalDate? {
    val date = trim().take(10)
    return try {
        LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-M-d"))
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun String.toFiniteDoubleOrNull(): Double? =
    Regex("""[-+]?\d+(?:\.\d+)?""")
        .find(replace(",", ""))
        ?.value
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)

internal fun electricityRoomKey(room: YxyElectricityRoom): String =
    listOf(room.areaId, room.buildingCode, room.floorCode, room.roomCode).joinToString("#")

internal class ElectricityHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun snapshots(room: YxyElectricityRoom, mdtype: String): List<ElectricityBalanceSnapshot> =
        read().snapshots
            .filter { it.roomKey == electricityRoomKey(room) && it.mdtype == mdtype }
            .sortedBy(ElectricityBalanceSnapshot::recordedAt)

    suspend fun label(room: YxyElectricityRoom, mdtype: String): String? =
        read().meterLabels.firstOrNull {
            it.roomKey == electricityRoomKey(room) && it.mdtype == mdtype
        }?.label

    suspend fun setLabel(room: YxyElectricityRoom, mdtype: String, label: String?) {
        require(label == null || label in METER_LABELS) { "表计用途无效" }
        context.electricityHistoryDataStore.edit { preferences ->
            val current = decode(preferences[electricityDataKey])
            val roomKey = electricityRoomKey(room)
            val labels = current.meterLabels.filterNot { it.roomKey == roomKey && it.mdtype == mdtype }
            preferences[electricityDataKey] = json.encodeToString(
                ElectricityHistoryData.serializer(),
                current.copy(meterLabels = if (label == null) labels else labels + ElectricityMeterLabel(roomKey, mdtype, label)),
            )
        }
    }

    suspend fun record(room: YxyElectricityRoom, meter: YxyElectricityMeter, now: Long = System.currentTimeMillis()) {
        val balance = meter.remainingKwh?.takeIf { it.isFinite() } ?: return
        val roomKey = electricityRoomKey(room)
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        context.electricityHistoryDataStore.edit { preferences ->
            val current = decode(preferences[electricityDataKey])
            val kept = current.snapshots.filterNot {
                it.roomKey == roomKey &&
                    it.mdtype == meter.mdtype &&
                    Instant.ofEpochMilli(it.recordedAt).atZone(ZoneId.systemDefault()).toLocalDate() == date
            }
            val snapshot = ElectricityBalanceSnapshot(roomKey, meter.mdtype, balance, now)
            preferences[electricityDataKey] = json.encodeToString(
                ElectricityHistoryData.serializer(),
                current.copy(snapshots = (kept + snapshot).takeLast(MAX_SNAPSHOTS)),
            )
        }
    }

    suspend fun clear() {
        context.electricityHistoryDataStore.edit { it.remove(electricityDataKey) }
    }

    private suspend fun read(): ElectricityHistoryData {
        val raw = context.electricityHistoryDataStore.data.first()[electricityDataKey] ?: return ElectricityHistoryData()
        return decode(raw)
    }

    private fun decode(raw: String?): ElectricityHistoryData {
        if (raw == null) return ElectricityHistoryData()
        return try {
            json.decodeFromString(ElectricityHistoryData.serializer(), raw)
        } catch (error: SerializationException) {
            throw IllegalStateException("电费历史数据损坏，请清除易校园插件数据后重试", error)
        }
    }

    private companion object {
        const val MAX_SNAPSHOTS = 500
        val METER_LABELS = setOf("照明", "空调", "其他")
    }
}
