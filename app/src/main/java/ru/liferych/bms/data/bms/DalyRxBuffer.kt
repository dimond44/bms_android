package ru.liferych.bms.data.bms

import android.util.Log

/**
 * Assembles Daly 13-byte A5 frames from a BLE notification byte stream.
 * Thread-safe via [synchronized] on the internal buffer (legacy behaviour).
 */
class DalyRxBuffer {
    private val rxBuffer = mutableListOf<Byte>()

    /**
     * Appends [bytes] and returns every complete valid frame extracted.
     * Invalid frames are skipped one byte at a time (same as legacy).
     */
    fun appendAndExtract(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val frames = mutableListOf<ByteArray>()
        synchronized(rxBuffer) {
            for (b in bytes) rxBuffer.add(b)
            while (rxBuffer.size >= DalyProtocol.FRAME_SIZE) {
                val start = rxBuffer.indexOf(DalyProtocol.FRAME_START)
                if (start < 0) {
                    rxBuffer.clear()
                    break
                }
                repeat(start) { rxBuffer.removeAt(0) }
                if (rxBuffer.size < DalyProtocol.FRAME_SIZE) break
                val frame = ByteArray(DalyProtocol.FRAME_SIZE) { index -> rxBuffer[index] }
                if (DalyProtocol.isValidFrame(frame)) {
                    repeat(DalyProtocol.FRAME_SIZE) { rxBuffer.removeAt(0) }
                    frames.add(frame)
                } else {
                    Log.w(TAG, "invalid Daly frame: ${DalyProtocol.bytesToHex(frame)}")
                    rxBuffer.removeAt(0)
                }
            }
        }
        return frames
    }

    fun clear() {
        synchronized(rxBuffer) { rxBuffer.clear() }
    }

    companion object {
        private const val TAG = "DalyParser"
    }
}
