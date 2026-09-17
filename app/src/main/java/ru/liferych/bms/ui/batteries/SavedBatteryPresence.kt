package ru.liferych.bms.ui.batteries

import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.ui.model.SavedBatteryCardStatus

/**
 * Pure legacy-equivalent presence rules for «Мои батареи» cards.
 *
 * Matches MainActivity.resolveBatteryPresence / hasFreshTelemetryForSelected:
 * - GATT Connected + fresh lastUpdatedAt (≤30s) → Connected
 * - GATT Connected without fresh frame → Checking
 * - MAC in current discovered list → Connected (ads presence)
 * - scan not finished → Checking
 * - else → Disconnected
 *
 * Stale BatteryState alone never yields Connected when Disconnected.
 */
object SavedBatteryPresence {
    /** Legacy PRESENCE_TELEMETRY_FRESH_MS. */
    const val FRESH_MS = 30_000L

    /**
     * @param address saved battery MAC
     * @param connection live BLE connection state
     * @param telemetry current BatteryState (may be stale)
     * @param discovered devices from the active/last presence scan list
     * @param scanDone true after presence scan finished
     * @param selectedAddress currently selected MAC (for Error mapping)
     * @param nowMs clock for freshness
     */
    fun resolve(
        address: String,
        connection: BmsConnectionState,
        telemetry: BatteryState,
        discovered: List<BmsDevice>,
        scanDone: Boolean,
        selectedAddress: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): SavedBatteryCardStatus {
        if (connection is BmsConnectionState.Error &&
            selectedAddress.equals(address, true)
        ) {
            return SavedBatteryCardStatus.ConnectionError
        }
        if (connection is BmsConnectionState.Connecting &&
            connection.deviceAddress.equals(address, true)
        ) {
            // Presence already found this MAC — keep «В сети» during GATT connect.
            if (discovered.any { it.address.equals(address, true) }) {
                return SavedBatteryCardStatus.Connected
            }
            return SavedBatteryCardStatus.Checking
        }
        if (connection is BmsConnectionState.Connected &&
            connection.deviceAddress.equals(address, true)
        ) {
            return if (isFresh(telemetry, nowMs)) {
                SavedBatteryCardStatus.Connected
            } else {
                SavedBatteryCardStatus.Checking
            }
        }
        if (discovered.any { it.address.equals(address, true) }) {
            return SavedBatteryCardStatus.Connected
        }
        // «Проверяем…» only for the initial presence pass — soft rescans that set
        // connection=Scanning must NOT flip Offline cards back to Checking forever.
        if (!scanDone) {
            return SavedBatteryCardStatus.Checking
        }
        return SavedBatteryCardStatus.Disconnected
    }

    fun isFresh(telemetry: BatteryState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val updated = telemetry.lastUpdatedAt ?: return false
        return nowMs - updated <= FRESH_MS
    }
}
