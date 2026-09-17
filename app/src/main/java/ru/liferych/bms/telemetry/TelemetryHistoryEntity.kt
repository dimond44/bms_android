package ru.liferych.bms.telemetry

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Compact local Voltage/Current history for Compose charts.
 *
 * Independent of the upload buffer ([TelemetryPointEntity]): SYNCED upload rows
 * may be purged after 3 days, while chart history is retained longer.
 */
@Entity(
    tableName = "telemetry_history",
    indices = [
        Index(value = ["bmsUid", "recordedAt"]),
    ],
)
data class TelemetryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bmsUid: String,
    val recordedAt: Long,
    val voltage: Double?,
    val current: Double?,
)
