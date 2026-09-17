package ru.liferych.bms.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared identity cache used by legacy MainActivity (`bms_identity` prefs).
 *
 * Factory serial is produced by Modbus holding registers 0x0057–0x005D
 * ([MainActivity.factorySerialFromRegisters]) and persisted as `sn_<MAC>`.
 * Compose Dashboard reads the same keys — no second storage, no new Daly commands.
 */
class BmsIdentityStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Cached factory serial for [address] (BLE MAC), or null if absent.
     *
     * @param address BLE MAC used as key suffix (same format as legacy selectedAddress).
     */
    fun cachedFactorySerial(address: String?): String? {
        val key = address?.trim().orEmpty()
        if (key.isBlank()) return null
        return prefs.getString("sn_$key", null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Cached HW version ASCII for [address], or null if absent.
     */
    fun cachedHwVersion(address: String?): String? {
        val key = address?.trim().orEmpty()
        if (key.isBlank()) return null
        return prefs.getString("hw_$key", null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Persists HW version ASCII for [address] (legacy `hw_<MAC>` key).
     *
     * @param address BLE MAC
     * @param hwVersion non-blank ASCII from Daly 0x63
     */
    fun saveHwVersion(address: String, hwVersion: String) {
        val key = address.trim()
        val hw = hwVersion.trim()
        if (key.isBlank() || hw.isBlank()) return
        prefs.edit().putString("hw_$key", hw).apply()
    }

    /**
     * Legacy [MainActivity.displayFactorySn] display transform:
     * strip trailing BMS version tokens (R24TK / R24TH / R10K) if present in raw SN.
     */
    fun displayFactorySerial(raw: String?): String = Companion.displayFactorySerial(raw)

    companion object {
        const val PREFS_NAME = "bms_identity"
        private val BMS_VERSION_TOKENS = listOf("R24TK", "R24TH", "R10K")

        /**
         * Pure display transform (no Android deps) — same rules as legacy MainActivity.
         */
        fun displayFactorySerial(raw: String?): String {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return ""
            val upper = value.uppercase()
            val cut = BMS_VERSION_TOKENS
                .map { upper.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull()
                ?: -1
            return if (cut >= 0) value.substring(0, cut).trim() else value
        }
    }
}
