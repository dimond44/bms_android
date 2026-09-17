package ru.liferych.bms.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.liferych.bms.data.bms.BatteryStateMapper
import ru.liferych.bms.data.bms.DalyData
import ru.liferych.bms.data.bms.DalyFrameParser
import ru.liferych.bms.data.bms.DalyProtocol
import ru.liferych.bms.data.ble.DalyBleClient
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.repository.BmsRepository

/**
 * Real BMS repository: owns scan/connect/wake/poll/parse and publishes [BatteryState].
 *
 * Legacy MainActivity may attach [LegacyHost] for UI/config side-effects without owning GATT.
 */
class DalyBmsRepository(
    @Suppress("unused") private val appContext: Context,
    private val bleClient: DalyBleClient,
    private val mainHandler: Handler,
) : BmsRepository {
    interface LegacyHost {
        /** True while Modbus config / remote write must pause runtime poll. */
        fun shouldPauseRuntimePoll(): Boolean = false

        /** Optional Modbus/config diversion for non-Daly bytes. Returns true if consumed. */
        fun onRawNotification(bytes: ByteArray): Boolean = false

        fun onConnected(address: String) {}
        fun onDisconnected() {}
        fun onTelemetryUpdated() {}
        fun onDeviceFound(device: BmsDevice) {}
        fun onScanFinished(foundCount: Int) {}
        fun onBleError(message: String) {}
    }

    private val _batteryState = MutableStateFlow(BatteryState())
    private val _connectionState =
        MutableStateFlow<BmsConnectionState>(BmsConnectionState.Disconnected)
    private val _discoveredDevices = MutableStateFlow<List<BmsDevice>>(emptyList())

    override val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()
    override val connectionState: StateFlow<BmsConnectionState> = _connectionState.asStateFlow()
    override val discoveredDevices: StateFlow<List<BmsDevice>> = _discoveredDevices.asStateFlow()

    /** Shared mutable telemetry model (legacy MainActivity reads the same instance). */
    val dalyData: DalyData = DalyData()

    var legacyHost: LegacyHost? = null

    private var selectedAddress: String? = null
    private var selectedDeviceName: String = ""
    private var polling = false
    private var pollLoopToken = 0
    private var wakeInProgress = false
    private var wakeToken = 0
    private var wakeAttempt = 0
    private var runtimeCommands: List<Int> = emptyList()
    private var runtimeCommandIndex = 0
    private var pendingRuntimeCommand: Int? = null
    private var runtimeExpectedFrames = 1
    private val runtimeReceivedGroups: MutableSet<Int> = mutableSetOf()
    private val batteryCodeFrames: MutableMap<Int, String> = sortedMapOf()
    private val batteryCodeFrameHex: MutableMap<Int, String> = sortedMapOf()
    private val hwVersionFrames: MutableMap<Int, String> = sortedMapOf()
    private val hwVersionFrameHex: MutableMap<Int, String> = sortedMapOf()

    /** Factory SN / HW assembled from multi-frame ASCII cmds (0x57 / 0x63). */
    var batteryCodeAscii: String = ""
        private set
    var hwVersionAscii: String = ""
        private set

    val isPolling: Boolean get() = polling
    val connectedAddress: String? get() = selectedAddress
    val connectedName: String get() = selectedDeviceName

    init {
        bleClient.setHost(object : DalyBleClient.Host {
            override fun onConnectionState(state: BmsConnectionState) {
                publishConnection(state)
            }

            override fun onGattConnected(gatt: BluetoothGatt) {
                dalyData.cells.clear()
                dalyData.cellCount = null
                dalyData.errors.clear()
                dalyData.lastUpdatedAt = 0L
                batteryCodeFrames.clear()
                batteryCodeFrameHex.clear()
                hwVersionFrames.clear()
                hwVersionFrameHex.clear()
                batteryCodeAscii = ""
                hwVersionAscii = ""
                publishData(dalyData)
            }

            override fun onGattDisconnected() {
                cancelWakeSequence()
                stopPolling()
                dalyData.errors.clear()
                publishData(dalyData)
                legacyHost?.onDisconnected()
            }

            override fun onServicesFailed(reason: String) {
                Log.w(TAG, reason)
                stopPolling()
                publishConnection(BmsConnectionState.Error(reason))
                legacyHost?.onBleError(reason)
            }

            override fun onCharacteristicsReady(gatt: BluetoothGatt) {
                // Ensure previous poll/wake cannot race a new session.
                cancelWakeSequence()
                stopPolling()
                polling = true
                val address = selectedAddress ?: gatt.device.address
                selectedAddress = address
                publishConnection(BmsConnectionState.Connected(address))
                legacyHost?.onConnected(address)
            }

            override fun onNotificationsEnabled() {
                Log.i(TAG, "Notifications enabled — start wake")
                startBmsWakeSequence()
            }

            override fun onNotificationBytes(bytes: ByteArray) {
                handleIncoming(bytes)
            }

            override fun onDeviceFound(device: BmsDevice, bluetoothDevice: android.bluetooth.BluetoothDevice) {
                val current = _discoveredDevices.value.toMutableList()
                val idx = current.indexOfFirst { it.address.equals(device.address, true) }
                if (idx >= 0) {
                    current[idx] = device
                } else {
                    current.add(device)
                }
                // Soft prefer known Daly BLE name pattern (legacy isDalyBluetoothDeviceId).
                // Do not hide other devices — open scan never hard-filtered by UUID/prefix.
                _discoveredDevices.value = current.sortedWith(
                    compareBy<BmsDevice> { device ->
                        if (isLikelyDalyAdvertisedName(device.name)) 0 else 1
                    }.thenByDescending { it.rssi },
                )
                legacyHost?.onDeviceFound(device)
            }

            override fun onScanFinished(foundCount: Int) {
                Log.i(TAG, "Scan finished found=$foundCount")
                legacyHost?.onScanFinished(foundCount)
            }

            override fun onBleLog(message: String) {
                Log.i(TAG, message)
            }
        })
    }

    fun setPollingEnabled(enabled: Boolean) {
        if (enabled) {
            polling = true
        } else {
            stopPolling()
        }
    }

    /** Restart runtime poll after host-side writes (MOS / config). */
    fun requestPollSoon(delayMs: Long = 400L) {
        if (!polling) return
        mainHandler.postDelayed({
            if (polling) pollOnce()
        }, delayMs)
    }

    fun publishData(data: DalyData) {
        // Serial (factory SN) stays null: legacy reads Modbus holding 0x0057–0x005D.
        // HW version comes from already-polled Daly ASCII cmd 0x63.
        _batteryState.value = BatteryStateMapper.fromDalyData(
            data = data,
            factorySerial = null,
            bmsHwVersion = hwVersionAscii,
        )
    }

    fun publishConnection(state: BmsConnectionState) {
        _connectionState.value = state
    }

    fun publishDiscoveredDevices(devices: List<BmsDevice>) {
        _discoveredDevices.value = devices
    }

    override fun startScan() {
        _discoveredDevices.value = emptyList()
        bleClient.startScan()
    }

    override fun stopScan() {
        bleClient.stopScan()
    }

    override suspend fun connect(address: String) {
        connectNow(address)
    }

    fun connectNow(address: String) {
        selectedAddress = address
        val known = _discoveredDevices.value.firstOrNull {
            it.address.equals(address, true)
        }
        selectedDeviceName = known?.name.orEmpty()
        cancelWakeSequence()
        stopPolling()
        if (!bleClient.connect(address)) {
            publishConnection(BmsConnectionState.Error("Не удалось начать подключение"))
        }
    }

    override suspend fun disconnect() {
        disconnectNow()
    }

    fun disconnectNow() {
        cancelWakeSequence()
        stopPolling()
        bleClient.disconnect()
        selectedAddress = null
        selectedDeviceName = ""
        publishConnection(BmsConnectionState.Disconnected)
    }

    fun writeRaw(frame: ByteArray): Boolean = bleClient.writeRaw(frame)

    fun writeCommand(cmd: Int): Boolean = bleClient.writeCommand(cmd)

    private fun stopPolling() {
        polling = false
        pollLoopToken++
        pendingRuntimeCommand = null
    }

    fun cancelWake() {
        cancelWakeSequence()
    }

    private fun cancelWakeSequence() {
        wakeToken++
        wakeInProgress = false
        wakeAttempt = 0
    }

    /**
     * Пробуждение MCU Daly безопасным чтением 0x90.
     * Literal port of MainActivity.startBmsWakeSequence.
     */
    @SuppressLint("MissingPermission")
    private fun startBmsWakeSequence() {
        cancelWakeSequence()
        wakeInProgress = true
        val token = ++wakeToken
        wakeAttempt = 0
        Log.i(WAKE_TAG, "Starting wake/read sequence")
        sendWakeReadAttempt(token)
    }

    @SuppressLint("MissingPermission")
    private fun sendWakeReadAttempt(token: Int) {
        if (token != wakeToken || !wakeInProgress) return
        if (!polling || bleClient.bluetoothGatt == null || bleClient.writeCharacteristic == null) {
            cancelWakeSequence()
            return
        }
        wakeAttempt++
        if (wakeAttempt > WAKE_MAX_ATTEMPTS) {
            Log.w(WAKE_TAG, "No response after $WAKE_MAX_ATTEMPTS attempts — start normal poll")
            finishWakeSequence(gotResponse = false)
            return
        }
        val frame = DalyProtocol.buildRequest(0x90)
        Log.i(WAKE_TAG, "Sending wake/read request #$wakeAttempt: ${DalyProtocol.bytesToHex(frame)}")
        val ok = bleClient.writeRaw(frame)
        if (!ok) {
            Log.w(WAKE_TAG, "wake write failed attempt=$wakeAttempt")
        }
        val delay = WAKE_RETRY_DELAYS_MS.getOrElse(wakeAttempt - 1) { 500L }
        mainHandler.postDelayed({
            if (token != wakeToken || !wakeInProgress) return@postDelayed
            Log.i(WAKE_TAG, "No response")
            sendWakeReadAttempt(token)
        }, delay)
    }

    private fun onWakeResponseReceived() {
        if (!wakeInProgress) return
        Log.i(WAKE_TAG, "Response received")
        Log.i(TAG, "BMS ONLINE")
        finishWakeSequence(gotResponse = true)
    }

    private fun finishWakeSequence(gotResponse: Boolean) {
        wakeInProgress = false
        wakeToken++
        if (!polling) return
        mainHandler.postDelayed({
            if (polling) pollOnce()
        }, if (gotResponse) 120L else 300L)
    }

    @SuppressLint("MissingPermission")
    private fun pollOnce() {
        val token = ++pollLoopToken
        mainHandler.post { pollOnce(token) }
    }

    @SuppressLint("MissingPermission")
    private fun pollOnce(token: Int) {
        if (token != pollLoopToken) return
        if (!polling) return
        if (legacyHost?.shouldPauseRuntimePoll() == true) {
            mainHandler.postDelayed({ pollOnce(token) }, 1000)
            return
        }
        val gatt = bleClient.bluetoothGatt ?: return
        val ch = bleClient.writeCharacteristic ?: return
        runtimeCommands = buildList {
            add(DalyProtocol.DALY_HW_VERSION_CMD)
            add(DalyProtocol.DALY_BATTERY_CODE_CMD)
            addAll(DalyProtocol.RUNTIME_POLL_COMMANDS)
        }
        runtimeCommandIndex = 0
        pendingRuntimeCommand = null
        sendCurrentRuntimeCommand(gatt, ch, token)
    }

    @SuppressLint("MissingPermission")
    private fun sendCurrentRuntimeCommand(
        gatt: BluetoothGatt,
        ch: android.bluetooth.BluetoothGattCharacteristic,
        token: Int,
    ) {
        if (token != pollLoopToken) return
        if (!polling) return
        if (legacyHost?.shouldPauseRuntimePoll() == true) {
            mainHandler.postDelayed({ pollOnce(token) }, 1000)
            return
        }
        if (runtimeCommandIndex >= runtimeCommands.size) {
            mainHandler.postDelayed({ pollOnce(token) }, 4000)
            return
        }
        val command = runtimeCommands[runtimeCommandIndex]
        pendingRuntimeCommand = command
        runtimeReceivedGroups.clear()
        runtimeExpectedFrames = when (command) {
            DalyProtocol.DALY_HW_VERSION_CMD -> DalyProtocol.DALY_HW_VERSION_FRAMES
            DalyProtocol.DALY_BATTERY_CODE_CMD -> DalyProtocol.DALY_BATTERY_CODE_FRAMES
            0x95 -> ((dalyData.cellCount ?: 1) + 2) / 3
            0x96 -> ((dalyData.tempCount ?: 1) + 6) / 7
            else -> 1
        }.coerceAtLeast(1)
        val frame = DalyProtocol.buildRequest(command)
        val started = DalyBleCharacteristicsWrite(gatt, ch, frame)
        Log.d(
            TAG,
            "request cmd=0x%02X write=$started uuid=${ch.uuid}: ${DalyProtocol.bytesToHex(frame)}"
                .format(command),
        )
        if (!started) {
            pendingRuntimeCommand = null
            mainHandler.postDelayed({ sendCurrentRuntimeCommand(gatt, ch, token) }, 250)
            return
        }
        mainHandler.postDelayed({
            if (
                token == pollLoopToken &&
                pendingRuntimeCommand == command &&
                runtimeCommandIndex < runtimeCommands.size
            ) {
                Log.w(TAG, "timeout waiting response cmd=0x%02X".format(command))
                pendingRuntimeCommand = null
                runtimeCommandIndex++
                sendCurrentRuntimeCommand(gatt, ch, token)
            }
        }, 1800)
    }

    private fun DalyBleCharacteristicsWrite(
        gatt: BluetoothGatt,
        ch: android.bluetooth.BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean = ru.liferych.bms.data.ble.DalyBleCharacteristics.writeFrame(gatt, ch, frame)

    private fun completeRuntimeCommand(cmd: Int, payload: ByteArray) {
        if (pendingRuntimeCommand != cmd) return
        if (
            cmd == 0x95 ||
            cmd == 0x96 ||
            cmd == DalyProtocol.DALY_BATTERY_CODE_CMD ||
            cmd == DalyProtocol.DALY_HW_VERSION_CMD
        ) {
            runtimeReceivedGroups.add(payload[0].toInt() and 0xFF)
            if (runtimeReceivedGroups.size < runtimeExpectedFrames) return
        }
        pendingRuntimeCommand = null
        runtimeCommandIndex++
        val token = pollLoopToken
        val gatt = bleClient.bluetoothGatt ?: return
        val ch = bleClient.writeCharacteristic ?: return
        mainHandler.postDelayed({ sendCurrentRuntimeCommand(gatt, ch, token) }, 120)
    }

    private fun handleIncoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (legacyHost?.onRawNotification(bytes) == true) {
            legacyHost?.onTelemetryUpdated()
            return
        }
        val frames = bleClient.extractFrames(bytes)
        for (frame in frames) {
            parseFrame(frame)
        }
        legacyHost?.onTelemetryUpdated()
    }

    private fun parseFrame(frame: ByteArray) {
        val result = DalyFrameParser.applyFrame(frame, dalyData)
        val cmd = result.command
        val p = result.payload

        if (result.isAsciiBatteryCode) {
            if (storeAsciiCmdFrame(batteryCodeFrames, batteryCodeFrameHex, p)) {
                batteryCodeAscii = batteryCodeFrames.toSortedMap().values.joinToString("")
            }
        }
        if (result.isAsciiHwVersion) {
            if (storeAsciiCmdFrame(hwVersionFrames, hwVersionFrameHex, p)) {
                hwVersionAscii = hwVersionFrames.toSortedMap().values.joinToString("")
            }
        }

        if (wakeInProgress) {
            onWakeResponseReceived()
        }
        publishData(dalyData)
        selectedAddress?.let { address ->
            if (bleClient.bluetoothGatt != null) {
                publishConnection(BmsConnectionState.Connected(address))
            }
        }
        completeRuntimeCommand(cmd, p)
    }

    private fun storeAsciiCmdFrame(
        frames: MutableMap<Int, String>,
        hex: MutableMap<Int, String>,
        payload: ByteArray,
    ): Boolean {
        if (payload.isEmpty()) return false
        val index = payload[0].toInt() and 0xFF
        val chunk = payload.copyOfRange(1, payload.size)
            .map { it.toInt() and 0xFF }
            .takeWhile { it in 32..126 }
            .map { it.toChar() }
            .joinToString("")
        frames[index] = chunk
        hex[index] = DalyProtocol.hex(payload)
        return true
    }

    companion object {
        private const val TAG = "BmsRepository"
        private const val WAKE_TAG = "BMS-WAKE"
        private const val WAKE_MAX_ATTEMPTS = 5
        private val WAKE_RETRY_DELAYS_MS = longArrayOf(400L, 400L, 500L, 500L, 500L)

        /**
         * Legacy MainActivity.isDalyBluetoothDeviceId — advertised name like DL-&lt;hex&gt;.
         * Used only for soft list ordering, never to drop scan results.
         */
        fun isLikelyDalyAdvertisedName(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            return name.matches(Regex("^DL-[0-9A-Fa-f]+$"))
        }
    }
}
