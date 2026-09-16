package ru.liferych.bms.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice

/**
 * UI-facing BMS contract. Implementation lives in data layer.
 */
interface BmsRepository {
    val batteryState: StateFlow<BatteryState>
    val connectionState: StateFlow<BmsConnectionState>
    val discoveredDevices: StateFlow<List<BmsDevice>>

    suspend fun connect(address: String)

    suspend fun disconnect()

    fun startScan()

    fun stopScan()
}
