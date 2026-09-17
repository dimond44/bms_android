package ru.liferych.bms.telemetry

import android.content.Context
import android.util.Log

/**
 * Local Voltage/Current history for Compose charts.
 *
 * Writes happen together with telemetry enqueue (same sample, same timestamp).
 * Reads never invent offline/zero fillers.
 */
class TelemetryHistoryRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    private fun dao(): TelemetryHistoryDao =
        BmsLocalDatabase.get(appContext).telemetryHistoryDao()

    /**
     * Persists one measured sample when at least voltage or current is present.
     *
     * @param bmsUid server battery id
     * @param recordedAt capture epoch millis
     * @param voltage volts or null
     * @param current amperes (signed) or null
     */
    fun record(
        bmsUid: String,
        recordedAt: Long,
        voltage: Double?,
        current: Double?,
    ) {
        if (bmsUid.isBlank() || recordedAt <= 0L) return
        if (voltage == null && current == null) return
        dao().insert(
            TelemetryHistoryEntity(
                bmsUid = bmsUid,
                recordedAt = recordedAt,
                voltage = voltage,
                current = current,
            ),
        )
    }

    /**
     * Loads raw then display-downsampled history for [period] ending at [nowMs].
     *
     * Side effect: purges samples older than [RETENTION_MS].
     */
    fun loadChartState(
        bmsUid: String,
        period: TelemetryHistoryPeriod,
        nowMs: Long = System.currentTimeMillis(),
    ): TelemetryHistoryChartState {
        purgeExpired(nowMs)
        if (bmsUid.isBlank()) {
            return emptyState(period, nowMs)
        }
        val toMs = nowMs
        val fromMs = nowMs - period.durationMs
        val raw = dao().listInRange(bmsUid, fromMs, toMs).map {
            TelemetryHistoryPoint(
                recordedAt = it.recordedAt,
                voltage = it.voltage,
                current = it.current,
            )
        }
        val display = TelemetryHistoryDownsampler.forDisplay(raw, period)
        val voltages = raw.mapNotNull { it.voltage }
        val currents = raw.mapNotNull { it.current }
        return TelemetryHistoryChartState(
            period = period,
            fromMs = fromMs,
            toMs = toMs,
            points = display,
            voltageMin = voltages.minOrNull(),
            voltageMax = voltages.maxOrNull(),
            voltageAvg = voltages.takeIf { it.isNotEmpty() }?.average(),
            currentMin = currents.minOrNull(),
            currentAvg = currents.takeIf { it.isNotEmpty() }?.average(),
            currentMax = currents.maxOrNull(),
            rawCount = raw.size,
            loading = false,
            empty = display.isEmpty(),
        )
    }

    /**
     * Deletes local history older than retention. Does not touch server DB.
     */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - RETENTION_MS
        val deleted = dao().deleteOlderThan(cutoff)
        if (deleted > 0) {
            Log.i(TAG, "history purged=$deleted olderThan=$cutoff")
        }
    }

    private fun emptyState(
        period: TelemetryHistoryPeriod,
        nowMs: Long,
    ): TelemetryHistoryChartState {
        return TelemetryHistoryChartState(
            period = period,
            fromMs = nowMs - period.durationMs,
            toMs = nowMs,
            points = emptyList(),
            voltageMin = null,
            voltageMax = null,
            voltageAvg = null,
            currentMin = null,
            currentAvg = null,
            currentMax = null,
            rawCount = 0,
            empty = true,
        )
    }

    companion object {
        private const val TAG = "TelemetryHistory"
        /** Keep slightly more than the longest chart window (30d). */
        const val RETENTION_MS = 35L * 24L * 60L * 60L * 1000L

        /**
         * Gap threshold for chart path breaks (~3× upload cadence).
         */
        const val GAP_THRESHOLD_MS = 45_000L
    }
}
