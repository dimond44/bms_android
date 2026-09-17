package ru.liferych.bms.domain.model

/**
 * Immutable UI/domain snapshot of BMS telemetry.
 * Only fields that the current Daly runtime parser actually fills.
 *
 * Identity fields:
 * - [bmsHwVersion] from Daly ASCII cmd 0x63 (already in runtime poll).
 * - [factorySerial] is NOT filled from 0x90–0x98 / 0x57 alone.
 *   Legacy MainActivity reads factory SN from Modbus config registers
 *   (holding 0x0057–0x005D). TODO: wire that path for Compose when config
 *   read is shared without inventing new Daly BLE commands.
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
    /** Factory SN from Modbus config — null until shared config path exists. */
    val factorySerial: String? = null,
    /** Hardware version ASCII assembled from Daly cmd 0x63 frames. */
    val bmsHwVersion: String? = null,
)

data class CellState(
    val index: Int,
    val voltage: Double,
    val balancing: Boolean = false,
)
