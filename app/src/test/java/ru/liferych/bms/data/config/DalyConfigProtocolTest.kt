package ru.liferych.bms.data.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class DalyConfigProtocolTest {
    @Test
    fun writeSingle_matchesLegacyByteOrderAndCrc() {
        val frame = DalyConfigProtocol.writeSingle(
            slave = 0x81,
            register = 0x0174,
            value = 0x00A2,
        )

        assertArrayEquals(
            byteArrayOf(
                0x81.toByte(),
                0x06,
                0x01,
                0x74,
                0x00,
                0xA2.toByte(),
            ),
            frame.copyOfRange(0, 6),
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }

    @Test
    fun writeSingle_dischargeMosOn_matchesConfirmedLiveFrame() {
        // Confirmed live TX: D2 06 00 A6 00 01 BB 8A
        val frame = DalyConfigProtocol.writeSingle(
            slave = 0xD2,
            register = 0x00A6,
            value = 0x0001,
        )

        assertArrayEquals(
            byteArrayOf(
                0xD2.toByte(),
                0x06,
                0x00,
                0xA6.toByte(),
                0x00,
                0x01,
                0xBB.toByte(),
                0x8A.toByte(),
            ),
            frame,
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }

    @Test
    fun readHolding_dischargeMos_matchesConfirmedLiveFrame() {
        // Confirmed live TX: D2 03 00 A6 00 01 77 8A
        val frame = DalyConfigProtocol.readHolding(
            slave = 0xD2,
            start = 0x00A6,
            count = 1,
        )

        assertArrayEquals(
            byteArrayOf(
                0xD2.toByte(),
                0x03,
                0x00,
                0xA6.toByte(),
                0x00,
                0x01,
                0x77,
                0x8A.toByte(),
            ),
            frame,
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }

    @Test
    fun writeSingle_chargeMosOff_usesCalculatedCrc() {
        val frame = DalyConfigProtocol.writeSingle(
            slave = 0xD2,
            register = 0x00A5,
            value = 0x0000,
        )

        assertArrayEquals(
            byteArrayOf(
                0xD2.toByte(),
                0x06,
                0x00,
                0xA5.toByte(),
                0x00,
                0x00,
            ),
            frame.copyOfRange(0, 6),
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }

    @Test
    fun readHolding_matchesLegacyFc03Frame() {
        val frame = DalyConfigProtocol.readHolding(
            slave = 0x81,
            start = 0x0115,
            count = 1,
        )

        assertArrayEquals(
            byteArrayOf(
                0x81.toByte(),
                0x03,
                0x01,
                0x15,
                0x00,
                0x01,
            ),
            frame.copyOfRange(0, 6),
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }

    @Test
    fun timeSync_matchesLegacyHeaderWithoutByteCount() {
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.SEPTEMBER, 17, 15, 46, 30)
            set(Calendar.MILLISECOND, 0)
        }
        val frame = DalyConfigProtocol.timeSyncFrame(calendar)

        assertEquals(14, frame.size)
        assertArrayEquals(
            byteArrayOf(
                0x81.toByte(),
                0x10,
                0x01,
                0x23,
                0x00,
                0x03,
                26,
                9,
                17,
                15,
                46,
                30,
            ),
            frame.copyOfRange(0, 12),
        )
        assertTrue(DalyConfigProtocol.hasValidCrc(frame))
    }
}
