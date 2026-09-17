package ru.liferych.bms.telemetry

/**
 * Legacy MainActivity.dalyBluetoothDeviceId / bmsUid for telemetry identity.
 * UI display sanitization must not affect this value.
 */
object TelemetryBmsUid {
    private val DL_UID_REGEX = Regex("^DL-[0-9A-Fa-f]+$")

    /**
     * @param bluetoothAddress BLE MAC
     * @param bluetoothName advertised / saved BLE name
     * @return server bms_uid
     */
    fun resolve(bluetoothAddress: String?, bluetoothName: String): String {
        val advertised = bluetoothName.trim()
        if (advertised.matches(DL_UID_REGEX)) return advertised
        val macDigits = bluetoothAddress.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        if (macDigits.isNotBlank()) return "DL-$macDigits"
        return advertised.ifBlank { bluetoothAddress?.trim().orEmpty().ifBlank { "unknown_bms" } }
    }

    fun isStable(uid: String, address: String?): Boolean {
        if (uid.matches(DL_UID_REGEX)) return true
        return !address.isNullOrBlank() && uid.isNotBlank() && uid != "unknown_bms"
    }
}
