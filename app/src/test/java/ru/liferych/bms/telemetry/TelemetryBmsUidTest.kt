package ru.liferych.bms.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure bms_uid rules used by RuntimeTelemetryUploader (no Android deps).
 */
class TelemetryBmsUidTest {

    @Test
    fun prefersDlAdvertisedName() {
        assertEquals(
            "DL-AABBCCDDEEFF",
            TelemetryBmsUid.resolve("AA:BB:CC:DD:EE:FF", "DL-AABBCCDDEEFF"),
        )
    }

    @Test
    fun fromMacDigits() {
        assertEquals(
            "DL-AABBCCDDEEFF",
            TelemetryBmsUid.resolve("AA:BB:CC:DD:EE:FF", ""),
        )
    }

    @Test
    fun unknownWhenEmpty() {
        assertEquals("unknown_bms", TelemetryBmsUid.resolve(null, ""))
    }
}
