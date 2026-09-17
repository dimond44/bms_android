package ru.liferych.bms.telemetry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TelemetryHistoryDao {
    /**
     * Inserts one history sample. Never invents missing voltage/current.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(point: TelemetryHistoryEntity): Long

    /**
     * Raw samples in [fromInclusive, toInclusive], ascending by time.
     */
    @Query(
        """
        SELECT * FROM telemetry_history
        WHERE bmsUid = :bmsUid
          AND recordedAt >= :fromInclusive
          AND recordedAt <= :toInclusive
          AND (voltage IS NOT NULL OR current IS NOT NULL)
        ORDER BY recordedAt ASC
        """,
    )
    fun listInRange(
        bmsUid: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): List<TelemetryHistoryEntity>

    /**
     * Deletes samples older than [olderThanExclusive] across all batteries.
     *
     * @return deleted row count
     */
    @Query("DELETE FROM telemetry_history WHERE recordedAt < :olderThanExclusive")
    fun deleteOlderThan(olderThanExclusive: Long): Int
}
