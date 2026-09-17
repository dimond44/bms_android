package ru.liferych.bms.telemetry

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TelemetryPointEntity::class,
        TelemetryHistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class BmsLocalDatabase : RoomDatabase() {
    abstract fun telemetryPointDao(): TelemetryPointDao
    abstract fun telemetryHistoryDao(): TelemetryHistoryDao

    companion object {
        @Volatile
        private var instance: BmsLocalDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS telemetry_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bmsUid TEXT NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        voltage REAL,
                        current REAL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_telemetry_history_bmsUid_recordedAt
                    ON telemetry_history(bmsUid, recordedAt)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Singleton Room DB for offline telemetry buffer + chart history.
         *
         * @param context application context
         * @return database instance
         */
        fun get(context: Context): BmsLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BmsLocalDatabase::class.java,
                    "liferych_bms_local.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
