package ru.liferych.bms

import android.app.Application
import ru.liferych.bms.telemetry.TelemetrySyncScheduler

/**
 * Application entry: shared BLE/BMS container + telemetry sync.
 */
class BmsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TelemetrySyncScheduler.ensurePeriodic(this)
        TelemetrySyncScheduler.enqueueImmediate(this)
    }
}
