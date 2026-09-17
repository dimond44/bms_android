package ru.liferych.bms.ui.fake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.model.CellState
import ru.liferych.bms.domain.repository.BmsRepository

/**
 * Stable demo [BmsRepository] for Compose UI development.
 * Values do not change over time — no random / ticker updates.
 *
 * Must not be used with real BLE. Real [ru.liferych.bms.data.repository.DalyBmsRepository]
 * is wired in a later stage after UI approval.
 */
class FakeBmsRepository : BmsRepository {
    private val demoAddress = "AA:BB:CC:DD:EE:01"

    private val demoCells = listOf(
        CellState(1, 3.245, balancing = true),
        CellState(2, 3.251, balancing = false),
        CellState(3, 3.248, balancing = false),
        CellState(4, 3.262, balancing = true),
        CellState(5, 3.255, balancing = false),
        CellState(6, 3.241, balancing = false),
        CellState(7, 3.258, balancing = false),
        CellState(8, 3.249, balancing = false),
        CellState(9, 3.266, balancing = true),
        CellState(10, 3.253, balancing = false),
        CellState(11, 3.247, balancing = false),
        CellState(12, 3.260, balancing = false),
        CellState(13, 3.244, balancing = false),
        CellState(14, 3.257, balancing = false),
        CellState(15, 3.252, balancing = false),
        CellState(16, 3.239, balancing = false),
    )

    private val demoBattery = BatteryState(
        voltage = 51.8,
        current = -12.4,
        soc = 78.0,
        remainingCapacityAh = 83.0,
        fullCapacityAh = 106.4,
        temperatures = listOf(27, 26, 28),
        minTemp = 26,
        maxTemp = 28,
        cells = demoCells,
        cellDiffV = demoCells.maxOf { it.voltage } - demoCells.minOf { it.voltage },
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        cycleCount = 142,
        cellCount = 16,
        chargerConnected = false,
        loadConnected = true,
        balancingCells = setOf(1, 4, 9),
        errors = emptyList(),
        lastUpdatedAt = 1_725_000_000_000L,
        // Preview-only identity; production serial needs Modbus config path.
        factorySerial = "224LG151200441",
        bmsHwVersion = "JHB-R24TK-V2.1",
    )

    private val _batteryState = MutableStateFlow(demoBattery)
    private val _connectionState =
        MutableStateFlow<BmsConnectionState>(BmsConnectionState.Connected(demoAddress))
    private val _discoveredDevices = MutableStateFlow(
        listOf(
            BmsDevice(address = demoAddress, name = "DALY-BMS-16S", rssi = -58),
            BmsDevice(address = "AA:BB:CC:DD:EE:02", name = "DALY-BMS-Garage", rssi = -72),
        ),
    )

    override val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()
    override val connectionState: StateFlow<BmsConnectionState> = _connectionState.asStateFlow()
    override val discoveredDevices: StateFlow<List<BmsDevice>> = _discoveredDevices.asStateFlow()

    /**
     * Demo helper: switch connection presentation without BLE.
     * Used by previews / future UI QA toggles only.
     */
    fun setDemoConnection(state: BmsConnectionState) {
        _connectionState.value = state
        if (state !is BmsConnectionState.Connected) {
            _batteryState.value = BatteryState()
        } else {
            _batteryState.value = demoBattery
        }
    }

    override suspend fun connect(address: String) {
        _connectionState.value = BmsConnectionState.Connected(address)
        _batteryState.value = demoBattery
    }

    override suspend fun disconnect() {
        _connectionState.value = BmsConnectionState.Disconnected
        _batteryState.value = BatteryState()
    }

    override fun startScan() {
        _connectionState.value = BmsConnectionState.Scanning
    }

    override fun stopScan() {
        if (_connectionState.value is BmsConnectionState.Scanning) {
            _connectionState.value = BmsConnectionState.Disconnected
        }
    }
}
