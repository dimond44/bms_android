package ru.liferych.bms.ui.batteries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.ui.model.SavedBatteryCardStatus

/**
 * Presence rules: Connected/Disconnected must not use stale BatteryState alone.
 */
class SavedBatteryPresenceTest {

    private val mac = "AA:BB:CC:DD:EE:FF"
    private val now = 1_700_000_000_000L

    @Test
    fun connected_withFreshTelemetry_isOnline() {
        val status = SavedBatteryPresence.resolve(
            address = mac,
            connection = BmsConnectionState.Connected(mac),
            telemetry = BatteryState(voltage = 13.2, soc = 99.0, lastUpdatedAt = now - 1_000),
            discovered = emptyList(),
            scanDone = true,
            selectedAddress = mac,
            nowMs = now,
        )
        assertEquals(SavedBatteryCardStatus.Connected, status)
    }

    @Test
    fun connected_withoutFreshTelemetry_isChecking() {
        val status = SavedBatteryPresence.resolve(
            address = mac,
            connection = BmsConnectionState.Connected(mac),
            telemetry = BatteryState(voltage = 13.2, soc = 99.0, lastUpdatedAt = now - 60_000),
            discovered = emptyList(),
            scanDone = true,
            selectedAddress = mac,
            nowMs = now,
        )
        assertEquals(SavedBatteryCardStatus.Checking, status)
    }

    @Test
    fun disconnected_withStaleTelemetry_isOffline() {
        val status = SavedBatteryPresence.resolve(
            address = mac,
            connection = BmsConnectionState.Disconnected,
            telemetry = BatteryState(voltage = 13.2, soc = 99.0, lastUpdatedAt = now - 1_000),
            discovered = emptyList(),
            scanDone = true,
            selectedAddress = mac,
            nowMs = now,
        )
        assertEquals(SavedBatteryCardStatus.Disconnected, status)
    }

    @Test
    fun error_isConnectionError() {
        val status = SavedBatteryPresence.resolve(
            address = mac,
            connection = BmsConnectionState.Error("fail"),
            telemetry = BatteryState(),
            discovered = emptyList(),
            scanDone = true,
            selectedAddress = mac,
            nowMs = now,
        )
        assertEquals(SavedBatteryCardStatus.ConnectionError, status)
    }

    @Test
    fun discoveredAds_isOnline() {
        val status = SavedBatteryPresence.resolve(
            address = mac,
            connection = BmsConnectionState.Disconnected,
            telemetry = BatteryState(),
            discovered = listOf(BmsDevice(address = mac, name = "DL-AABBCCDDEEFF", rssi = -50)),
            scanDone = true,
            selectedAddress = null,
            nowMs = now,
        )
        assertEquals(SavedBatteryCardStatus.Connected, status)
    }

    @Test
    fun reconnect_fresh_isOnline() {
        assertTrue(
            SavedBatteryPresence.isFresh(
                BatteryState(lastUpdatedAt = now - 5_000),
                nowMs = now,
            ),
        )
        assertFalse(
            SavedBatteryPresence.isFresh(
                BatteryState(lastUpdatedAt = now - 40_000),
                nowMs = now,
            ),
        )
    }
}
