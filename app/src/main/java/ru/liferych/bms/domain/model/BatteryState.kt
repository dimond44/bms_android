package ru.liferych.bms.domain.model

/**
 * Immutable UI/domain snapshot of BMS telemetry.
 * Only fields that the current Daly runtime parser actually fills.
 */
data class BatteryState(
    val voltage: Double? = null,
    val current: Double? = null,
    val soc: Double? = null,
    val remainingCapacityAh: Double? = null,
    val fullCapacityAh: Double? = null,
    val temperatures: List<Int> = emptyList(),
    val minTemp: Int? = null,
    val maxTemp: Int? = null,
    val cells: List<CellState> = emptyList(),
    val cellDiffV: Double? = null,
    val chargeMosEnabled: Boolean? = null,
    val dischargeMosEnabled: Boolean? = null,
    val cycleCount: Int? = null,
    val cellCount: Int? = null,
    val chargerConnected: Boolean? = null,
    val loadConnected: Boolean? = null,
    val balancingCells: Set<Int> = emptySet(),
    val errors: List<String> = emptyList(),
    val lastUpdatedAt: Long? = null,
)

data class CellState(
    val index: Int,
    val voltage: Double,
    val balancing: Boolean = false,
)
