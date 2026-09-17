package ru.liferych.bms.telemetry

/**
 * One Voltage/Current sample for chart rendering (display layer).
 *
 * @property recordedAt epoch millis (UTC wall clock at capture)
 * @property voltage volts, or null when absent
 * @property current amperes (signed; negative = charge), or null when absent
 */
data class TelemetryHistoryPoint(
    val recordedAt: Long,
    val voltage: Double?,
    val current: Double?,
)

/**
 * Immutable chart payload prepared off the UI thread.
 */
data class TelemetryHistoryChartState(
    val period: TelemetryHistoryPeriod,
    val fromMs: Long,
    val toMs: Long,
    val points: List<TelemetryHistoryPoint>,
    val voltageMin: Double?,
    val voltageMax: Double?,
    val voltageAvg: Double?,
    val currentMin: Double?,
    val currentAvg: Double?,
    val currentMax: Double?,
    val rawCount: Int,
    val loading: Boolean = false,
    val empty: Boolean = false,
)

/**
 * User-selectable chart window. Default is [H24].
 */
enum class TelemetryHistoryPeriod(
    val label: String,
    val durationMs: Long,
    val maxDisplayPoints: Int,
) {
    H1("1 ч", 1L * 60L * 60L * 1000L, 360),
    H6("6 ч", 6L * 60L * 60L * 1000L, 480),
    H24("24 ч", 24L * 60L * 60L * 1000L, 720),
    D7("7 дн", 7L * 24L * 60L * 60L * 1000L, 720),
    D30("30 дн", 30L * 24L * 60L * 60L * 1000L, 720),
}
