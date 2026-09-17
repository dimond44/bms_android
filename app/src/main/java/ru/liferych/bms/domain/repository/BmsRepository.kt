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

    suspend fun connect(address: String, bluetoothName: String = "")

    suspend fun disconnect()

    /**
     * @param clearResults when false, keep previous discovery list (soft presence refresh)
     */
    fun startScan(clearResults: Boolean = true)

    /**
     * Continuous BLE presence scan until [stopScan] (no timed auto-stop).
     * Ads only — does not connect GATT.
     */
    fun startPresenceScan(clearResults: Boolean = false)

    fun stopScan()
}
