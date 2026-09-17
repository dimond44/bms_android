package ru.liferych.bms.data.local

/**
 * Locally persisted CLIENT battery entry.
 *
 * Same fields as legacy MainActivity SavedBattery JSON in SharedPreferences
 * `"saved_batteries"` / key `"items"`.
 *
 * @param address BLE MAC — stable technical id (never replaced by custom name).
 * @param bluetoothName advertised BLE name at last save.
 * @param customName user-assigned display name (long-press rename); may be blank.
 * @param soc last known SOC snapshot (optional).
 * @param capacityAh last known full capacity Ah (optional).
 * @param lastSeenAt epoch millis of last upsert.
 */
data class SavedBattery(
    val address: String,
    val bluetoothName: String,
    val customName: String,
    val soc: Double?,
    val capacityAh: Double?,
    val lastSeenAt: Long,
) {
    /**
     * Display title: custom name → bluetooth name → address.
     */
    fun displayName(): String {
        return customName.ifBlank { bluetoothName.ifBlank { address } }
    }
}
