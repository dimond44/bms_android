package ru.liferych.bms

import android.app.Application
import ru.liferych.bms.telemetry.TelemetrySyncScheduler

/**
 * Application entry: планирует фоновую синхронизацию telemetry.
 */
class BmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetrySyncScheduler.ensurePeriodic(this)
        TelemetrySyncScheduler.enqueueImmediate(this)
    }
}
