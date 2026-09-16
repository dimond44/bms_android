package ru.liferych.bms.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.liferych.bms.data.bms.BatteryStateMapper
import ru.liferych.bms.data.bms.DalyData
import ru.liferych.bms.data.ble.DalyBleClient
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.repository.BmsRepository

/**
 * Repository bridging Daly BLE transport + parsed telemetry to StateFlows.
 *
 * During the extraction stage, legacy MainActivity still owns GattCallback lifecycle
 * and calls [publishData] / [publishConnection] after existing side effects.
 * connect/scan methods are available for the future UI layer.
 */
class DalyBmsRepository(
    private val bleClient: DalyBleClient,
) : BmsRepository {
    private val _batteryState = MutableStateFlow(BatteryState())
    private val _connectionState =
        MutableStateFlow<BmsConnectionState>(BmsConnectionState.Disconnected)
    private val _discoveredDevices = MutableStateFlow<List<BmsDevice>>(emptyList())

    override val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()
    override val connectionState: StateFlow<BmsConnectionState> = _connectionState.asStateFlow()
    override val discoveredDevices: StateFlow<List<BmsDevice>> = _discoveredDevices.asStateFlow()

    init {
        bleClient.setListener(object : DalyBleClient.Listener {
            override fun onConnectionState(state: BmsConnectionState) {
                _connectionState.value = state
            }

            override fun onDalyFrame(frame: ByteArray) {
                // Frames are still applied by MainActivity during migration.
            }

            override fun onBleLog(message: String) {
                // Host may mirror into legacy bleDebugText.
            }
        })
    }

    /** Called by legacy host after DalyData mutation. */
    fun publishData(data: DalyData) {
        _batteryState.value = BatteryStateMapper.fromDalyData(data)
    }

    fun publishConnection(state: BmsConnectionState) {
        _connectionState.value = state
        bleClient.publishConnectionState(state)
    }

    fun publishDiscoveredDevices(devices: List<BmsDevice>) {
        _discoveredDevices.value = devices
    }

    override suspend fun connect(address: String) {
        // Host still performs connectGatt; expose state only.
        publishConnection(BmsConnectionState.Connecting(address))
    }

    override suspend fun disconnect() {
        bleClient.disconnect()
        publishConnection(BmsConnectionState.Disconnected)
    }

    override fun startScan() {
        publishConnection(BmsConnectionState.Scanning)
    }

    override fun stopScan() {
        if (_connectionState.value is BmsConnectionState.Scanning) {
            publishConnection(BmsConnectionState.Disconnected)
        }
    }
}
