package ru.liferych.bms.telemetry

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная telemetry point: сначала Room, потом sync на backend.
 *
 * syncState: PENDING | SYNCING | SYNCED | FAILED
 */
@Entity(
    tableName = "telemetry_points",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["syncState", "recordedAt"]),
        Index(value = ["bmsUid", "recordedAt"]),
    ],
)
data class TelemetryPointEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val eventId: String,
    val bmsUid: String,
    val recordedAt: Long,
    val payloadJson: String,
    val syncState: String,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
)
