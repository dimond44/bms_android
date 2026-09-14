package ru.liferych.bms.telemetry

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TelemetryPointEntity::class], version = 1, exportSchema = false)
abstract class BmsLocalDatabase : RoomDatabase() {
    abstract fun telemetryPointDao(): TelemetryPointDao

    companion object {
        @Volatile
        private var instance: BmsLocalDatabase? = null

        /**
         * Назначение: singleton Room DB для offline telemetry buffer.
         * @param context application context
         * @return database instance
         */
        fun get(context: Context): BmsLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BmsLocalDatabase::class.java,
                    "liferych_bms_local.db",
                ).build().also { instance = it }
            }
        }
    }
}
