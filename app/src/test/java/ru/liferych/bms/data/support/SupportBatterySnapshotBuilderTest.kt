package ru.liferych.bms.data.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liferych.bms.domain.auth.UserProfile
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.CellState

/**
 * Parity/glue tests for warranty battery_snapshot builder (no network).
 */
class SupportBatterySnapshotBuilderTest {

    @Test
    fun resolveBmsUid_prefersDlAdvertisedName() {
        assertEquals(
            "DL-AABBCCDDEEFF",
            SupportBatterySnapshotBuilder.resolveBmsUid("AA:BB:CC:DD:EE:FF", "DL-AABBCCDDEEFF"),
        )
    }

    @Test
    fun resolveBmsUid_fromMacDigits() {
        assertEquals(
            "DL-AABBCCDDEEFF",
            SupportBatterySnapshotBuilder.resolveBmsUid("AA:BB:CC:DD:EE:FF", ""),
        )
    }

    @Test
    fun build_includesTelemetryAndOwner_withoutInventedConfig() {
        val battery = BatteryState(
            voltage = 53.2,
            current = -1.5,
            soc = 80.0,
            remainingCapacityAh = 80.0,
            fullCapacityAh = 100.0,
            cellCount = 16,
            minTemp = 20,
            maxTemp = 22,
            cellDiffV = 0.012,
            chargeMosEnabled = true,
            dischargeMosEnabled = true,
            cells = listOf(CellState(1, 3.301), CellState(2, 3.310)),
            temperatures = listOf(20, 22),
            errors = listOf("ok"),
            bmsHwVersion = "JHB-R24TK-V2.1",
        )
        val owner = UserProfile(
            fullName = "Test User",
            phoneE164 = "+79001112233",
            email = "t@example.com",
        )
        val json = SupportBatterySnapshotBuilder.build(
            bmsUid = "DL-AABBCCDDEEFF",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "DL-AABBCCDDEEFF",
            battery = battery,
            factorySerial = "224LG151200441",
            owner = owner,
        )
        assertEquals("DL-AABBCCDDEEFF", json.getString("bms_uid"))
        assertEquals("AA:BB:CC:DD:EE:FF", json.getString("bluetooth_address"))
        assertEquals("224LG151200441", json.getString("bms_sn"))
        assertEquals("JHB-R24TK-V2.1", json.getString("bms_hw_version"))
        assertEquals("user", json.getString("source"))
        assertEquals("Test User", json.getString("owner_name"))
        assertEquals(3.301, json.getDouble("min_cell_v"), 0.0001)
        assertEquals(3.310, json.getDouble("max_cell_v"), 0.0001)
        assertEquals(100.0, json.getDouble("nominal_capacity_ah"), 0.01)
        assertEquals("runtime_remaining_ah_div_soc", json.getString("capacity_source"))
        assertTrue(json.has("event_id"))
        assertTrue(json.has("api_key"))
        assertFalse(json.has("raw"))
        assertFalse(json.has("events"))
        assertFalse(json.has("hardware_family"))
        assertFalse(json.has("config"))
    }
}
