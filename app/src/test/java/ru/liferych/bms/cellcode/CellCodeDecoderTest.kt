package ru.liferych.bms.cellcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellCodeDecoderTest {

    @Test
    fun normalize_joinsCrLfParts() {
        val raw = "04qcb76836300j\nbc40000903"
        assertEquals(
            "04QCB76836300JBC40000903",
            CellCodeNormalizer.normalize(raw)
        )
    }

    @Test
    fun eve_reference_code_full() {
        val result = CellCodeDecoder.decode("04qcb76836300jbc40000903")
        assertEquals(CellCodeRecognition.FULL, result.recognition)
        assertEquals("04QCB76836300JBC40000903", result.normalizedCode)
        assertEquals("EVE", result.manufacturer)
        assertEquals("Аккумуляторный элемент", result.productType)
        assertEquals("LiFePO4", result.batteryType)
        assertEquals(280.0, result.nominalCapacityAh!!, 0.01)
        assertEquals(3.2, result.nominalVoltageV!!, 0.01)
        assertEquals("LF280K", result.productSeries)
        assertEquals("2021-12-04", result.productionDateIso)
        assertEquals(false, result.recycled)
    }

    @Test
    fun catl_reference_code() {
        val result = CellCodeDecoder.decode("001cb240000003b3c0221953")
        assertTrue(
            result.recognition == CellCodeRecognition.FULL ||
                result.recognition == CellCodeRecognition.PARTIAL
        )
        assertEquals("CATL", result.manufacturer)
        assertEquals("Аккумуляторный элемент", result.productType)
        assertEquals("LiFePO4", result.batteryType)
        assertEquals(302.0, result.nominalCapacityAh!!, 0.01)
        assertEquals(3.2, result.nominalVoltageV!!, 0.01)
        assertNotNull(result.productionDateIso)
    }

    @Test
    fun invalid_short_code_fails() {
        val result = CellCodeDecoder.decode("A765101015")
        assertEquals(CellCodeRecognition.FAILED, result.recognition)
        assertNull(result.manufacturer)
    }

    @Test
    fun wrong_order_fourth_not_cpm_partial_or_failed() {
        val result = CellCodeDecoder.decode("jbc4000090304qcb76836300")
        // 4th char is '4' — not C/P/M
        assertTrue(
            result.recognition == CellCodeRecognition.FAILED ||
                result.recognition == CellCodeRecognition.PARTIAL
        )
        assertNull(result.productType)
    }

    @Test
    fun product_type_codes() {
        assertEquals(
            "Аккумуляторный элемент",
            CellCodeDecoder.decode("04QCB76836300JBC40000903").productType
        )
        // Force pack type letter at position 4
        val pack = CellCodeDecoder.decode("04QPB76836300JBC40000903")
        assertEquals("Батарея (Pack)", pack.productType)
        val module = CellCodeDecoder.decode("04QMB76836300JBC40000903")
        assertEquals("Батарейный модуль", module.productType)
    }

    @Test
    fun unknown_manufacturer_does_not_invent_eve() {
        val result = CellCodeDecoder.decode("ZZZCB76836300JBC40000903")
        assertNull(result.manufacturer)
        assertEquals("Аккумуляторный элемент", result.productType)
        assertEquals("LiFePO4", result.batteryType)
        assertTrue(
            result.recognition == CellCodeRecognition.PARTIAL ||
                result.recognition == CellCodeRecognition.FULL
        )
    }

    @Test
    fun production_date_decoder() {
        val d = CellCodeDecoder.decodeProductionDate("BC4")
        assertNotNull(d)
        assertEquals("2021-12-04", d!!.first)
    }

    @Test
    fun recycled_length_19() {
        val code19 = "04QCB76836300JBC400" // 19 chars structural sample
        assertEquals(19, code19.length)
        val result = CellCodeDecoder.decode(code19)
        assertEquals(true, result.recycled)
        assertFalse(result.recognition == CellCodeRecognition.FAILED && result.productType == null)
    }
}
