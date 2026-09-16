package ru.liferych.bms.data.bms

import android.util.Log

/**
 * Result of applying one validated 13-byte Daly A5 frame to [DalyData].
 * Side effects that belong to Activity/business layer are reported as flags.
 */
data class DalyFrameApplyResult(
    val command: Int,
    val recognized: Boolean,
    val payload: ByteArray,
    val cellCountChanged: Boolean = false,
    val previousCellCount: Int? = null,
    val isAsciiBatteryCode: Boolean = false,
    val isAsciiHwVersion: Boolean = false,
)

/**
 * Pure Daly A5 frame parser (no Android UI).
 * Mutates [DalyData] exactly as the legacy MainActivity.parseFrame telemetry path.
 */
object DalyFrameParser {
    private const val TAG = "DalyParser"

    /**
     * Applies a validated 13-byte frame to [data].
     * Caller must ensure [DalyProtocol.isValidFrame] first.
     */
    fun applyFrame(frame: ByteArray, data: DalyData): DalyFrameApplyResult {
        val cmd = frame[2].toInt() and 0xFF
        val p = frame.copyOfRange(4, 12)
        data.raw["0x%02X".format(cmd)] = DalyProtocol.hex(frame)

        var recognized = true
        var cellCountChanged = false
        var previousCellCount: Int? = null
        var isAsciiBatteryCode = false
        var isAsciiHwVersion = false

        when (cmd) {
            DalyProtocol.CMD_VOLTAGE_CURRENT_SOC -> {
                data.voltage = DalyProtocol.u16(p, 0) / 10.0
                data.current = (DalyProtocol.u16(p, 4) - 30000) / 10.0
                data.soc = DalyProtocol.u16(p, 6) / 10.0
            }
            DalyProtocol.CMD_CELL_EXTREMES -> {
                data.maxCellV = DalyProtocol.u16(p, 0) / 1000.0
                data.maxCellNo = p[2].toInt() and 0xFF
                data.minCellV = DalyProtocol.u16(p, 3) / 1000.0
                data.minCellNo = p[5].toInt() and 0xFF
            }
            DalyProtocol.CMD_TEMP_EXTREMES -> {
                data.maxTemp = (p[0].toInt() and 0xFF) - 40
                data.minTemp = (p[2].toInt() and 0xFF) - 40
            }
            DalyProtocol.CMD_MOS_REMAINING -> {
                data.chargeMos = (p[1].toInt() and 0xFF) != 0
                data.dischargeMos = (p[2].toInt() and 0xFF) != 0
                data.remainingAh = DalyProtocol.u32(p, 4) / 1000.0
            }
            DalyProtocol.CMD_STATUS -> {
                previousCellCount = data.cellCount
                data.cellCount = p[0].toInt() and 0xFF
                data.tempCount = p[1].toInt() and 0xFF
                data.chargerConnected = (p[2].toInt() and 0xFF) != 0
                data.loadConnected = (p[3].toInt() and 0xFF) != 0
                data.cycles = DalyProtocol.u16(p, 5)
                pruneCellsToCount(data)
                cellCountChanged = previousCellCount != data.cellCount
            }
            DalyProtocol.DALY_BATTERY_CODE_CMD -> {
                isAsciiBatteryCode = true
            }
            DalyProtocol.DALY_HW_VERSION_CMD -> {
                isAsciiHwVersion = true
            }
            DalyProtocol.CMD_CELL_VOLTAGES -> {
                val group = p[0].toInt() and 0xFF
                for (i in 0 until 3) {
                    val off = 1 + i * 2
                    val mv = DalyProtocol.u16(p, off)
                    val cellNo = (group - 1) * 3 + i + 1
                    val count = data.cellCount
                    if (count != null && cellNo > count) continue
                    if (mv in 500..5000) data.cells[cellNo] = mv / 1000.0
                }
            }
            DalyProtocol.CMD_TEMPERATURES -> {
                val group = p[0].toInt() and 0xFF
                for (i in 0 until 7) {
                    val idx = 1 + i
                    val raw = p[idx].toInt() and 0xFF
                    val tempNo = (group - 1) * 7 + i + 1
                    val count = data.tempCount
                    if (count != null && tempNo > count) continue
                    if (raw != 0x00 && raw != 0xFF) data.temps[tempNo] = raw - 40
                }
            }
            DalyProtocol.CMD_BALANCING -> {
                data.balancingCells.clear()
                for (byteIndex in 0 until 6) {
                    val mask = p[byteIndex].toInt() and 0xFF
                    for (bit in 0..7) {
                        if (((mask shr bit) and 1) != 0) {
                            val cellNo = byteIndex * 8 + bit + 1
                            val count = data.cellCount
                            if (count == null || cellNo <= count) {
                                data.balancingCells.add(cellNo)
                            }
                        }
                    }
                }
            }
            DalyProtocol.CMD_ERRORS -> {
                data.errors.clear()
                for (byteIndex in p.indices) {
                    val byteValue = p[byteIndex].toInt() and 0xFF
                    for (bitInByte in 0..7) {
                        if (((byteValue shr bitInByte) and 1) == 1) {
                            val globalBit = byteIndex * 8 + bitInByte
                            val description = DalyProtocol.errorDescription(globalBit)
                            if (!description.startsWith("Резерв") &&
                                !description.startsWith("Дополнительный fault code")
                            ) {
                                data.errors.add(description)
                            }
                        }
                    }
                }
            }
            else -> recognized = false
        }

        data.lastUpdatedAt = System.currentTimeMillis()
        updateDerived(data)
        // android.util.Log is unavailable in plain JVM unit tests — never fail parsing for logging.
        try {
            Log.d(TAG, "parsed cmd=0x%02X recognized=$recognized".format(cmd))
        } catch (_: RuntimeException) {
            // no-op in unit tests without Android Log mock
        }
        return DalyFrameApplyResult(
            command = cmd,
            recognized = recognized,
            payload = p,
            cellCountChanged = cellCountChanged,
            previousCellCount = previousCellCount,
            isAsciiBatteryCode = isAsciiBatteryCode,
            isAsciiHwVersion = isAsciiHwVersion,
        )
    }

    fun pruneCellsToCount(data: DalyData) {
        val count = data.cellCount ?: return
        val stale = data.cells.keys.filter { it < 1 || it > count }
        if (stale.isEmpty()) return
        for (key in stale) data.cells.remove(key)
    }

    fun updateDerived(data: DalyData) {
        pruneCellsToCount(data)
        if (hasTemperatureSensorError(data)) {
            data.temps.clear()
            data.minTemp = null
            data.maxTemp = null
        }
        if (data.cells.isNotEmpty()) {
            val vals = data.cells.values
            data.cellDiffV = vals.maxOrNull()!! - vals.minOrNull()!!
        }
        val soc = data.soc
        val rem = data.remainingAh
        if (soc != null && soc > 0.0 && rem != null) {
            data.estimatedFullAh = rem / (soc / 100.0)
        }
    }

    fun hasTemperatureSensorError(data: DalyData): Boolean {
        return data.errors.any {
            it.contains("Ошибка датчика температуры ячеек", ignoreCase = true) ||
                it.contains("Ошибка датчика температуры MOS", ignoreCase = true) ||
                it.contains("temp sensor", ignoreCase = true)
        }
    }
}
