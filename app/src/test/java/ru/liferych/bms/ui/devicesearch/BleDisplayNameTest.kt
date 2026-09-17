package ru.liferych.bms.ui.devicesearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: Daly AD names with NUL / U+FFFD garbage must not show «i�» in UI.
 */
class BleDisplayNameTest {
    @Test
    fun cleanIdUnchanged() {
        assertEquals("DL-D21A07122CC4", sanitizeBleDisplayName("DL-D21A07122CC4"))
    }

    @Test
    fun dropsTrailingReplacementChar() {
        assertEquals(
            "DL-D21A07122CC4",
            sanitizeBleDisplayName("DL-D21A07122CC4\uFFFD"),
        )
    }

    @Test
    fun dropsSpaceIBeforeReplacementChar() {
        assertEquals(
            "DL-D21A07122CC4",
            sanitizeBleDisplayName("DL-D21A07122CC4 i\uFFFD"),
        )
    }

    @Test
    fun truncatesAtNulAndDropsReplacementChar() {
        val dirty = "DL-D21A07122CC4\u0000i\uFFFD"
        assertEquals("DL-D21A07122CC4", sanitizeBleDisplayName(dirty))
    }

    @Test
    fun dropsReplacementWithoutNulKeepsAttachedLetter() {
        // No space before i — cannot tell junk from real suffix without NUL.
        assertEquals(
            "DL-D21A07122C96i",
            sanitizeBleDisplayName("DL-D21A07122C96i\uFFFD"),
        )
    }

    @Test
    fun keepsCyrillicUserAlias() {
        assertEquals("Батарея дома", sanitizeBleDisplayName("Батарея дома"))
        assertEquals(
            "Батарея дома",
            deviceUiDisplayName(
                customName = "Батарея дома",
                bluetoothName = "DL-D21A07122CC4\u0000i\uFFFD",
                fallback = "--",
            ),
        )
    }

    @Test
    fun keepsNormalIdsAndSpaces() {
        assertEquals("DL-41190401807D", sanitizeBleDisplayName("DL-41190401807D"))
        assertEquals("SberBoom 4921", sanitizeBleDisplayName("SberBoom 4921"))
        assertEquals("DALY-BMS_16S", sanitizeBleDisplayName("DALY-BMS_16S"))
    }

    @Test
    fun nullOrBlank() {
        assertEquals("", sanitizeBleDisplayName(null))
        assertEquals("", sanitizeBleDisplayName(""))
        assertEquals("", sanitizeBleDisplayName("\u0000trash"))
    }

    @Test
    fun savedBatteryUiTitlePrefersAliasWithoutSanitize() {
        assertEquals(
            "Батарея дома",
            savedBatteryUiTitle(
                customName = "Батарея дома",
                bluetoothName = "DL-D21A07122CC4\u0000i\uFFFD",
                address = "D2:1A:07:12:2C:C4",
            ),
        )
    }

    @Test
    fun savedBatteryUiTitleSanitizesBluetoothName() {
        assertEquals(
            "DL-D21A07122CC4",
            savedBatteryUiTitle(
                customName = "",
                bluetoothName = "DL-D21A07122CC4\u0000i\uFFFD",
                address = "D2:1A:07:12:2C:C4",
            ),
        )
    }

    @Test
    fun deviceUiDisplayNameDashboardUsesDashNotMac() {
        assertEquals(
            "DL-D21A07122CC4",
            deviceUiDisplayName(
                customName = "",
                bluetoothName = "DL-D21A07122CC4\u0000i\uFFFD",
                fallback = "--",
            ),
        )
        assertEquals(
            "--",
            deviceUiDisplayName(
                customName = "",
                bluetoothName = "\u0000i\uFFFD",
                fallback = "--",
            ),
        )
    }
}
