package ru.liferych.bms.data.config

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.liferych.bms.data.bms.DalyProtocol
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.domain.model.BmsConnectionState
import java.util.Calendar

/**
 * Single application-level owner for Daly config-register I/O.
 *
 * Local Diagnostics and remote admin writes use this class so FC06/FC03 framing,
 * notification routing, and telemetry-poll pausing cannot diverge.
 */
class ConfigRegisterWriter(
    private val repository: DalyBmsRepository,
) {
    private val sessionMutex = Mutex()

    /** True while one local or remote config operation owns the BLE transport. */
    val isBusy: Boolean
        get() = sessionMutex.isLocked || repository.isConfigIoBusy

    /**
     * Runs [block] with exclusive config-I/O ownership.
     *
     * @throws ConfigIoUnavailableException when BMS disconnects or legacy config
     * I/O already owns the transport.
     */
    suspend fun <T> withSession(
        block: suspend Session.() -> T,
    ): T = sessionMutex.withLock {
        if (repository.connectionState.value !is BmsConnectionState.Connected) {
            throw ConfigIoUnavailableException(ConfigIoUnavailableReason.Disconnected)
        }
        val router = ModbusResponseRouter()
        if (!repository.beginConfigIo(router)) {
            throw ConfigIoUnavailableException(ConfigIoUnavailableReason.Busy)
        }
        try {
            Session(repository, router).block()
        } finally {
            repository.endConfigIo(router)
            repository.requestPollSoon(LEGACY_POLL_RESUME_DELAY_MS)
        }
    }

    /**
     * BLE config session. Its methods must only be called inside [withSession].
     */
    class Session internal constructor(
        private val repository: DalyBmsRepository,
        private val router: ModbusResponseRouter,
    ) {
        /**
         * Writes server-prepared [rawValue] to [register] with the legacy
         * time-sync → unlock → FC06 sequence (config slave 0x81).
         *
         * @return true when all BLE writes were accepted; readback is separate.
         */
        suspend fun writeSingleRegister(register: Int, rawValue: Int): Boolean {
            return writeFrames(
                listOf(DalyConfigProtocol.writeSingle(LEGACY_SLAVE, register, rawValue)),
            )
        }

        /**
         * Direct FC06 write without config unlock/preflight.
         *
         * Used by confirmed MOS control (slave 0xD2). Waits for Modbus FC06 echo.
         *
         * @return true when BLE accepts the write and a valid FC06 echo arrives.
         */
        suspend fun writeSingleRegisterDirect(
            slave: Int,
            register: Int,
            rawValue: Int,
            timeoutMs: Long = LEGACY_READ_TIMEOUT_MS,
        ): Boolean {
            val frame = DalyConfigProtocol.writeSingle(slave, register, rawValue)
            val deferred = router.expectWriteEcho(register, rawValue)
            if (!writeRaw(frame)) {
                router.cancelWriteEcho(deferred)
                return false
            }
            val ok = withTimeoutOrNull(timeoutMs) { deferred.await() } == true
            router.cancelWriteEcho(deferred)
            if (!ok) {
                Log.w(
                    TAG,
                    "FC06 echo timeout slave=0x%02X register=0x%04X".format(slave, register),
                )
            }
            return ok
        }

        /**
         * Direct FC03 read of one holding register without config unlock/preflight.
         *
         * @return unsigned 16-bit raw value, or null on timeout/write failure.
         */
        suspend fun readRegisterDirect(
            slave: Int,
            register: Int,
            timeoutMs: Long = LEGACY_READ_TIMEOUT_MS,
        ): Int? {
            val deferred = router.expectRead()
            if (!writeRaw(DalyConfigProtocol.readHolding(slave, register, 1))) {
                router.cancelRead(deferred)
                return null
            }
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
            router.cancelRead(deferred)
            if (raw == null) {
                Log.w(
                    TAG,
                    "FC03 timeout slave=0x%02X register=0x%04X".format(slave, register),
                )
            }
            return raw
        }

        /**
         * Writes prebuilt config [frames]. Each frame receives the same legacy
         * time-sync and unlock preflight used by local template writes.
         *
         * @return false on the first BLE write rejection.
         */
        suspend fun writeFrames(frames: List<ByteArray>): Boolean {
            for ((index, frame) in frames.withIndex()) {
                if (!writeRaw(DalyConfigProtocol.timeSyncFrame())) return false
                delay(LEGACY_TIME_TO_UNLOCK_DELAY_MS)
                if (!writeRaw(DalyConfigProtocol.writeSingle(
                        LEGACY_SLAVE,
                        LEGACY_UNLOCK_REGISTER,
                        LEGACY_UNLOCK_VALUE,
                    ))
                ) {
                    return false
                }
                delay(LEGACY_UNLOCK_TO_WRITE_DELAY_MS)
                if (!writeRaw(frame)) return false
                if (index < frames.lastIndex) delay(LEGACY_MULTI_FRAME_DELAY_MS)
            }
            return true
        }

        /**
         * Reads one holding register through Modbus FC03.
         *
         * @return unsigned 16-bit raw value, or null on timeout/write failure.
         */
        suspend fun readRegister(
            register: Int,
            timeoutMs: Long = LEGACY_READ_TIMEOUT_MS,
        ): Int? = readRegisters(listOf(register), timeoutMs)?.get(register)

        /**
         * Reads [registers] after one legacy time-sync/unlock preflight.
         *
         * @return complete address/value map, or null if any requested register
         * could not be read. Partial data is never reported as verified.
         */
        suspend fun readRegisters(
            registers: List<Int>,
            timeoutMs: Long = LEGACY_READ_TIMEOUT_MS,
        ): Map<Int, Int>? {
            val addresses = registers.distinct()
            if (addresses.isEmpty()) return emptyMap()
            if (!writeRaw(DalyConfigProtocol.timeSyncFrame())) return null
            delay(LEGACY_READ_TIME_TO_UNLOCK_DELAY_MS)
            if (!writeRaw(DalyConfigProtocol.writeSingle(
                    LEGACY_SLAVE,
                    LEGACY_UNLOCK_REGISTER,
                    LEGACY_UNLOCK_VALUE,
                ))
            ) {
                return null
            }
            delay(LEGACY_READ_UNLOCK_TO_REQUEST_DELAY_MS)

            val result = LinkedHashMap<Int, Int>()
            for ((index, register) in addresses.withIndex()) {
                val deferred = router.expectRead()
                if (!writeRaw(DalyConfigProtocol.readHolding(LEGACY_SLAVE, register, 1))) {
                    router.cancelRead(deferred)
                    return null
                }
                val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
                router.cancelRead(deferred)
                if (raw == null) {
                    Log.w(TAG, "read timeout register=0x%04X".format(register))
                    return null
                }
                result[register] = raw
                if (index < addresses.lastIndex) delay(LEGACY_READ_GAP_MS)
            }
            return result
        }

        /**
         * Waits without releasing config ownership.
         */
        suspend fun waitFor(milliseconds: Long) {
            delay(milliseconds)
        }

        /**
         * Sends [frame] on the main thread because Android GATT is main-owned.
         */
        private suspend fun writeRaw(frame: ByteArray): Boolean {
            if (repository.connectionState.value !is BmsConnectionState.Connected) {
                return false
            }
            return withContext(Dispatchers.Main.immediate) {
                repository.writeRaw(frame)
            }
        }
    }

    private companion object {
        private const val TAG = "ConfigRegisterWriter"
        private const val LEGACY_SLAVE = 0x81
        private const val LEGACY_UNLOCK_REGISTER = 0x0174
        private const val LEGACY_UNLOCK_VALUE = 0x00A2
        private const val LEGACY_TIME_TO_UNLOCK_DELAY_MS = 140L
        private const val LEGACY_UNLOCK_TO_WRITE_DELAY_MS = 250L
        private const val LEGACY_MULTI_FRAME_DELAY_MS = 200L
        private const val LEGACY_READ_TIME_TO_UNLOCK_DELAY_MS = 500L
        private const val LEGACY_READ_UNLOCK_TO_REQUEST_DELAY_MS = 700L
        private const val LEGACY_READ_GAP_MS = 250L
        private const val LEGACY_READ_TIMEOUT_MS = 5_000L
        private const val LEGACY_POLL_RESUME_DELAY_MS = 400L
    }
}

enum class ConfigIoUnavailableReason {
    Busy,
    Disconnected,
}

/**
 * Signals that a config session could not safely acquire the BLE transport.
 */
class ConfigIoUnavailableException(
    val reason: ConfigIoUnavailableReason,
) : IllegalStateException("Config I/O unavailable: $reason")

/**
 * Exact Daly Modbus builders shared by local and remote config writers.
 */
internal object DalyConfigProtocol {
    /**
     * Builds legacy Daly HCI time synchronization frame.
     */
    fun timeSyncFrame(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val payload = byteArrayOf(
            ((calendar.get(Calendar.YEAR) - 2000) and 0xFF).toByte(),
            ((calendar.get(Calendar.MONTH) + 1) and 0xFF).toByte(),
            (calendar.get(Calendar.DAY_OF_MONTH) and 0xFF).toByte(),
            (calendar.get(Calendar.HOUR_OF_DAY) and 0xFF).toByte(),
            (calendar.get(Calendar.MINUTE) and 0xFF).toByte(),
            (calendar.get(Calendar.SECOND) and 0xFF).toByte(),
        )
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0x81.toByte()
        frame[1] = 0x10.toByte()
        frame[2] = 0x01.toByte()
        frame[3] = 0x23.toByte()
        frame[4] = 0x00.toByte()
        frame[5] = 0x03.toByte()
        System.arraycopy(payload, 0, frame, 6, payload.size)
        appendCrc(frame)
        return frame
    }

    /**
     * Builds Modbus FC06 with big-endian register/value and low-byte-first CRC.
     */
    fun writeSingle(slave: Int, register: Int, value: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = (slave and 0xFF).toByte()
        frame[1] = 0x06
        frame[2] = ((register shr 8) and 0xFF).toByte()
        frame[3] = (register and 0xFF).toByte()
        frame[4] = ((value shr 8) and 0xFF).toByte()
        frame[5] = (value and 0xFF).toByte()
        appendCrc(frame)
        return frame
    }

    /**
     * Builds Modbus FC03 holding-register request.
     */
    fun readHolding(slave: Int, start: Int, count: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = (slave and 0xFF).toByte()
        frame[1] = 0x03
        frame[2] = ((start shr 8) and 0xFF).toByte()
        frame[3] = (start and 0xFF).toByte()
        frame[4] = ((count shr 8) and 0xFF).toByte()
        frame[5] = (count and 0xFF).toByte()
        appendCrc(frame)
        return frame
    }

    /**
     * Computes Modbus CRC16 (polynomial 0xA001).
     */
    fun crc16(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (index in 0 until length) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Validates either observed CRC byte order accepted by legacy parser.
     */
    fun hasValidCrc(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val calculated = crc16(frame, frame.size - 2)
        val lowHigh = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        val highLow = ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or
            (frame[frame.size - 1].toInt() and 0xFF)
        return calculated == lowHigh || calculated == highLow
    }

    /**
     * Appends low-byte-first CRC to a frame with two reserved trailing bytes.
     */
    private fun appendCrc(frame: ByteArray) {
        val crc = crc16(frame, frame.size - 2)
        frame[frame.size - 2] = (crc and 0xFF).toByte()
        frame[frame.size - 1] = ((crc shr 8) and 0xFF).toByte()
    }
}

/**
 * Routes fragmented Modbus notifications to the active FC03/FC06 waiter.
 */
internal class ModbusResponseRouter : DalyBmsRepository.ConfigIoHost {
    private val buffer = mutableListOf<Byte>()
    private var pendingRead: CompletableDeferred<Int?>? = null
    private var pendingWriteEcho: CompletableDeferred<Boolean>? = null
    private var expectedWriteRegister: Int? = null
    private var expectedWriteValue: Int? = null

    /**
     * Arms one FC03 response waiter.
     */
    @Synchronized
    fun expectRead(): CompletableDeferred<Int?> {
        check(pendingRead == null) { "A Modbus read is already pending" }
        check(pendingWriteEcho == null) { "A Modbus write echo is already pending" }
        return CompletableDeferred<Int?>().also { pendingRead = it }
    }

    /**
     * Arms one FC06 echo waiter for the given register/value.
     */
    @Synchronized
    fun expectWriteEcho(register: Int, value: Int): CompletableDeferred<Boolean> {
        check(pendingRead == null) { "A Modbus read is already pending" }
        check(pendingWriteEcho == null) { "A Modbus write echo is already pending" }
        expectedWriteRegister = register
        expectedWriteValue = value
        return CompletableDeferred<Boolean>().also { pendingWriteEcho = it }
    }

    /**
     * Clears [deferred] if it is still the active waiter.
     */
    @Synchronized
    fun cancelRead(deferred: CompletableDeferred<Int?>) {
        if (pendingRead === deferred) pendingRead = null
    }

    /**
     * Clears [deferred] if it is still the active FC06 waiter.
     */
    @Synchronized
    fun cancelWriteEcho(deferred: CompletableDeferred<Boolean>) {
        if (pendingWriteEcho === deferred) {
            pendingWriteEcho = null
            expectedWriteRegister = null
            expectedWriteValue = null
        }
    }

    /**
     * Completes pending waiters immediately when GATT disconnects.
     */
    @Synchronized
    override fun onDisconnected() {
        pendingRead?.complete(null)
        pendingRead = null
        pendingWriteEcho?.complete(false)
        pendingWriteEcho = null
        expectedWriteRegister = null
        expectedWriteValue = null
        buffer.clear()
    }

    /**
     * Consumes non-Daly Modbus frames and completes the active FC03 waiter.
     */
    @Synchronized
    override fun onRawNotification(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (buffer.isEmpty() && bytes[0] == DalyProtocol.FRAME_START) return false
        buffer.addAll(bytes.toList())
        parseBufferedFrames()
        return true
    }

    /**
     * Extracts complete Modbus frames from the notification buffer.
     */
    private fun parseBufferedFrames() {
        while (buffer.size >= 5) {
            val start = (0 until buffer.lastIndex).firstOrNull { index ->
                buffer[index] != DalyProtocol.FRAME_START &&
                    (buffer[index + 1].toInt() and 0xFF) in MODBUS_FUNCTIONS
            } ?: run {
                if (buffer.size > MAX_BUFFER_BYTES) buffer.clear()
                return
            }
            repeat(start) { buffer.removeAt(0) }
            if (buffer.size < 5) return
            val function = buffer[1].toInt() and 0xFF
            val length = when (function) {
                0x03 -> {
                    if (buffer.size < 3) return
                    3 + (buffer[2].toInt() and 0xFF) + 2
                }
                0x06, 0x10 -> 8
                0x83, 0x86, 0x90 -> 5
                else -> {
                    buffer.removeAt(0)
                    continue
                }
            }
            if (buffer.size < length) return
            val frame = ByteArray(length) { buffer.removeAt(0) }
            if (!DalyConfigProtocol.hasValidCrc(frame)) continue
            when {
                function == 0x03 && (frame[2].toInt() and 0xFF) >= 2 -> {
                    val value = ((frame[3].toInt() and 0xFF) shl 8) or
                        (frame[4].toInt() and 0xFF)
                    pendingRead?.complete(value)
                    pendingRead = null
                }
                function == 0x06 && frame.size >= 8 -> {
                    val register = ((frame[2].toInt() and 0xFF) shl 8) or
                        (frame[3].toInt() and 0xFF)
                    val value = ((frame[4].toInt() and 0xFF) shl 8) or
                        (frame[5].toInt() and 0xFF)
                    val echoOk = expectedWriteRegister == register &&
                        expectedWriteValue == value
                    pendingWriteEcho?.complete(echoOk)
                    pendingWriteEcho = null
                    expectedWriteRegister = null
                    expectedWriteValue = null
                }
                function in setOf(0x83, 0x86, 0x90) -> {
                    pendingRead?.complete(null)
                    pendingRead = null
                    pendingWriteEcho?.complete(false)
                    pendingWriteEcho = null
                    expectedWriteRegister = null
                    expectedWriteValue = null
                }
            }
        }
    }

    private companion object {
        private const val MAX_BUFFER_BYTES = 1_024
        private val MODBUS_FUNCTIONS = setOf(0x03, 0x06, 0x10, 0x83, 0x86, 0x90)
    }
}
