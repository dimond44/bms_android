package ru.liferych.bms.data.local

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared persistence for CLIENT «Мои батареи».
 *
 * Uses the same SharedPreferences file and JSON schema as legacy MainActivity
 * so Compose and legacy UI share one list (no second storage).
 *
 * Service flavor session list stays in MainActivity (not persisted).
 */
class SavedBatteriesStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads saved batteries sorted by [SavedBattery.lastSeenAt] descending.
     */
    fun load(): List<SavedBattery> {
        val result = mutableListOf<SavedBattery>()
        val array = try {
            JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val address = item.optString("address").trim()
            if (address.isBlank()) continue
            result += SavedBattery(
                address = address,
                bluetoothName = item.optString("bluetooth_name"),
                customName = item.optString("custom_name"),
                soc = if (item.has("soc") && !item.isNull("soc")) item.optDouble("soc") else null,
                capacityAh = if (item.has("capacity_ah") && !item.isNull("capacity_ah")) {
                    item.optDouble("capacity_ah")
                } else {
                    null
                },
                lastSeenAt = item.optLong("last_seen_at"),
            )
        }
        return result.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Replaces the full saved list (legacy [saveBatteries] semantics).
     */
    fun save(items: List<SavedBattery>) {
        val array = JSONArray()
        items.forEach { battery ->
            array.put(
                JSONObject().apply {
                    put("address", battery.address)
                    put("bluetooth_name", battery.bluetoothName)
                    put("custom_name", battery.customName)
                    put("soc", battery.soc ?: JSONObject.NULL)
                    put("capacity_ah", battery.capacityAh ?: JSONObject.NULL)
                    put("last_seen_at", battery.lastSeenAt)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    /**
     * Inserts or updates one battery by BLE address; preserves existing customName.
     */
    fun upsert(
        address: String,
        bluetoothName: String,
        soc: Double? = null,
        capacityAh: Double? = null,
    ): SavedBattery {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.address.equals(address, ignoreCase = true) }
        val previous = current.getOrNull(index)
        val updated = SavedBattery(
            address = address,
            bluetoothName = bluetoothName.ifBlank { previous?.bluetoothName.orEmpty() },
            customName = previous?.customName.orEmpty(),
            soc = soc ?: previous?.soc,
            capacityAh = capacityAh ?: previous?.capacityAh,
            lastSeenAt = System.currentTimeMillis(),
        )
        if (index >= 0) current[index] = updated else current += updated
        save(current)
        return updated
    }

    /**
     * Sets custom display name for [address]. No-op if blank name.
     */
    fun rename(address: String, customName: String) {
        val name = customName.trim()
        if (name.isBlank()) return
        save(
            load().map {
                if (it.address.equals(address, ignoreCase = true)) {
                    it.copy(customName = name)
                } else {
                    it
                }
            },
        )
    }

    /**
     * Removes battery by address from local list.
     */
    fun remove(address: String) {
        save(load().filterNot { it.address.equals(address, ignoreCase = true) })
    }

    companion object {
        const val PREFS_NAME = "saved_batteries"
        const val KEY_ITEMS = "items"
    }
}
