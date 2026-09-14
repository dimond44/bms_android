package ru.liferych.bms.telemetry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TelemetryPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(point: TelemetryPointEntity): Long

    @Query(
        """
        SELECT * FROM telemetry_points
        WHERE syncState IN ('PENDING', 'FAILED')
        ORDER BY recordedAt ASC
        LIMIT :limit
        """
    )
    fun listPending(limit: Int): List<TelemetryPointEntity>

    @Query(
        """
        UPDATE telemetry_points
        SET syncState = :state, attemptCount = attemptCount + :attemptInc, lastError = :error
        WHERE localId IN (:ids)
        """
    )
    fun markState(ids: List<Long>, state: String, attemptInc: Int, error: String?)

    @Query(
        """
        UPDATE telemetry_points
        SET syncState = 'SYNCED', syncedAt = :syncedAt, lastError = NULL
        WHERE eventId IN (:eventIds)
        """
    )
    fun markSynced(eventIds: List<String>, syncedAt: Long)

    @Query("SELECT COUNT(*) FROM telemetry_points WHERE syncState IN ('PENDING', 'FAILED')")
    fun countPending(): Int

    @Query(
        """
        DELETE FROM telemetry_points
        WHERE syncState = 'SYNCED' AND syncedAt IS NOT NULL AND syncedAt < :olderThan
        """
    )
    fun deleteSyncedOlderThan(olderThan: Long): Int
}
