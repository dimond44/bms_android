package ru.liferych.bms.data.bms

import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.CellState

/**
 * Maps mutable [DalyData] to immutable [BatteryState] for UI/repository consumers.
 */
object BatteryStateMapper {
    /**
     * @param factorySerial optional factory SN (legacy Modbus path); not from 0x90–0x98.
     * @param bmsHwVersion optional HW version ASCII from Daly cmd 0x63.
     */
    fun fromDalyData(
        data: DalyData,
        factorySerial: String? = null,
        bmsHwVersion: String? = null,
    ): BatteryState {
        val balancing = data.balancingCells.toSet()
        val cells = data.cells.entries
            .sortedBy { it.key }
            .map { (index, voltage) ->
                CellState(
                    index = index,
                    voltage = voltage,
                    balancing = balancing.contains(index),
                )
            }
        val temperatures = data.temps.entries
            .sortedBy { it.key }
            .map { it.value }
        return BatteryState(
            voltage = data.voltage,
            current = data.current,
            soc = data.soc,
            remainingCapacityAh = data.remainingAh,
            fullCapacityAh = data.estimatedFullAh,
            temperatures = temperatures,
            minTemp = data.minTemp,
            maxTemp = data.maxTemp,
            cells = cells,
            cellDiffV = data.cellDiffV,
            chargeMosEnabled = data.chargeMos,
            dischargeMosEnabled = data.dischargeMos,
            cycleCount = data.cycles,
            cellCount = data.cellCount,
            chargerConnected = data.chargerConnected,
            loadConnected = data.loadConnected,
            balancingCells = balancing,
            errors = data.errors.toList(),
            lastUpdatedAt = data.lastUpdatedAt.takeIf { it > 0L },
            factorySerial = factorySerial?.trim()?.takeIf { it.isNotEmpty() },
            bmsHwVersion = bmsHwVersion?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
