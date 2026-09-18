package ru.liferych.bms.data.bms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Daly factory SN decoder (MainActivity-compatible).
 */
class DalyFactorySerialTest {

    @Test
    fun decode_asciiWithoutSwap() {
        // "224LG151200345" as packed HI/LO bytes (no swap)
        val sn = "224LG151200345"
        val registers = packRegisters(sn, swapBytes = false)
        assertEquals(sn, DalyFactorySerial.decode(registers, swapBytes = false))
    }

    @Test
    fun pickValid_rejectsBluetoothDlName() {
        assertFalse(DalyFactorySerial.isValid("DL-D21A08120AF8"))
        assertTrue(DalyFactorySerial.isValid("224LG151200345"))
    }

    @Test
    fun pickValid_fromRegisters() {
        val registers = packRegisters("224LG151200345", swapBytes = false)
        assertEquals("224LG151200345", DalyFactorySerial.pickValid(registers))
    }

    @Test
    fun decode_incompleteRegisters_returnsEmpty() {
        assertEquals("", DalyFactorySerial.decode(mapOf(0x0057 to 0x3232), swapBytes = false))
    }

    private fun packRegisters(ascii: String, swapBytes: Boolean): Map<Int, Int> {
        val bytes = ascii.toByteArray(Charsets.US_ASCII) + ByteArray(14) { 0 }
        val out = LinkedHashMap<Int, Int>()
        var i = 0
        for (addr in DalyFactorySerial.ADDRESSES) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            out[addr] = if (swapBytes) {
                (b1 shl 8) or b0
            } else {
                (b0 shl 8) or b1
            }
            i += 2
        }
        return out
    }
}
