package com.qzkt.timetable.model

import kotlinx.serialization.Serializable

/** 一次同步的结果。 */
@Serializable
data class SyncReport(
    val success: Boolean,
    val message: String,
    val totalSessions: Int = 0,
    val at: Long = System.currentTimeMillis(),
)
