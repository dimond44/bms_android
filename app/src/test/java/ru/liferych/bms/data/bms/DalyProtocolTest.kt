package ru.liferych.bms.data.bms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DalyProtocolTest {
    @Test
    fun buildRequest_isValid13ByteA5Frame() {
        val frame = DalyProtocol.buildRequest(0x90)
        assertEquals(13, frame.size)
        assertTrue(DalyProtocol.isValidFrame(frame))
        assertEquals(0x90, frame[2].toInt() and 0xFF)
    }

    @Test
    fun parseFrame_0x90_voltageCurrentSoc() {
        val payload = ByteArray(8)
        // voltage 48.0V => 480
        payload[0] = 0x01
        payload[1] = 0xE0.toByte()
        // current 0.0A => 30000
        payload[4] = 0x75
        payload[5] = 0x30
        // soc 55.5% => 555
        payload[6] = 0x02
        payload[7] = 0x2B
        val frame = DalyProtocol.buildA5Frame(0x90, payload)
        val data = DalyData()
        val result = DalyFrameParser.applyFrame(frame, data)
        assertEquals(0x90, result.command)
        assertEquals(48.0, data.voltage!!, 0.001)
        assertEquals(0.0, data.current!!, 0.001)
        assertEquals(55.5, data.soc!!, 0.001)
    }

    @Test
    fun rxBuffer_assemblesSplitFrames() {
        val frame = DalyProtocol.buildRequest(0x93)
        val buffer = DalyRxBuffer()
        val first = buffer.appendAndExtract(frame.copyOfRange(0, 5))
        assertTrue(first.isEmpty())
        val second = buffer.appendAndExtract(frame.copyOfRange(5, 13))
        assertEquals(1, second.size)
        assertTrue(DalyProtocol.isValidFrame(second[0]))
    }
}
