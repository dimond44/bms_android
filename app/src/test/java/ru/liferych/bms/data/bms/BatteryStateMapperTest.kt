package ru.liferych.bms.data.bms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liferych.bms.domain.model.CellState
import kotlin.math.abs

class BatteryStateMapperTest {
    @Test
    fun mapsCellsBalancingAndErrors() {
        val data = DalyData().apply {
            voltage = 51.2
            current = -3.0
            soc = 70.0
            remainingAh = 80.0
            cellCount = 2
            cells[1] = 3.25
            cells[2] = 3.27
            balancingCells.add(2)
            errors.add("Тестовая ошибка")
            chargeMos = true
            dischargeMos = false
        }
        val state = BatteryStateMapper.fromDalyData(data)
        assertEquals(51.2, state.voltage!!, 0.001)
        assertEquals(2, state.cells.size)
        assertTrue(state.cells.first { it.index == 2 }.balancing)
        assertEquals(listOf("Тестовая ошибка"), state.errors)
        assertEquals(true, state.chargeMosEnabled)
        assertEquals(false, state.dischargeMosEnabled)
    }

    @Test
    fun mapsHwVersionAndLeavesSerialNullByDefault() {
        val data = DalyData().apply { voltage = 13.2 }
        val state = BatteryStateMapper.fromDalyData(
            data = data,
            factorySerial = null,
            bmsHwVersion = "  JHB-R24TK-V2.1  ",
        )
        assertEquals(null, state.factorySerial)
        assertEquals("JHB-R24TK-V2.1", state.bmsHwVersion)
    }

    @Test
    fun cellMinMaxDeltaFromList() {
        val cells = listOf(
            CellState(1, 3.240, false),
            CellState(2, 3.266, true),
            CellState(3, 3.251, false),
        )
        val minV = cells.minOf { it.voltage }
        val maxV = cells.maxOf { it.voltage }
        val deltaMv = abs(maxV - minV) * 1000.0
        assertEquals(3.240, minV, 0.0001)
        assertEquals(3.266, maxV, 0.0001)
        assertEquals(26.0, deltaMv, 0.1)
    }
}
