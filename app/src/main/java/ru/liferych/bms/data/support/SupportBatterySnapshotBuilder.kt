package ru.liferych.bms.data.support

import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.BuildConfig
import ru.liferych.bms.domain.auth.UserProfile
import ru.liferych.bms.domain.model.BatteryState
import java.util.UUID

/**
 * Builds warranty `battery_snapshot` JSON using the same key names as legacy
 * [ru.liferych.bms.MainActivity.buildUploadJson] / [putHardwareIdentity].
 *
 * Only fills fields available in Compose domain/stores. Does not invent Modbus
 * config, raw frames, or historical events.
 *
 * @param bmsUid legacy-style uid (`DL-<MACdigits>` when possible)
 * @param bluetoothAddress BLE MAC
 * @param bluetoothName advertised / saved BLE name when known
 * @param battery live telemetry
 * @param factorySerial display SN from identity cache (may be blank)
 * @param owner optional authorized profile for owner_* fields
 */
object SupportBatterySnapshotBuilder {

    /**
     * @return snapshot object for `payload.battery_snapshot`
     */
    fun build(
        bmsUid: String,
        bluetoothAddress: String,
        bluetoothName: String,
        battery: BatteryState,
        factorySerial: String?,
        owner: UserProfile?,
    ): JSONObject {
        val advertised = bluetoothName.ifBlank { bmsUid }
        val hw = battery.bmsHwVersion?.takeIf { it.isNotBlank() }
        val sn = factorySerial?.takeIf { it.isNotBlank() }
            ?: battery.factorySerial?.takeIf { it.isNotBlank() }
        val nominal = nominalCapacityAh(battery)
        return JSONObject().apply {
            put("api_key", BmsApiConfig.API_KEY)
            put("bms_uid", bmsUid)
            put("bluetooth_name", advertised)
            put("bluetooth_address", bluetoothAddress)
            put("event_id", UUID.randomUUID().toString())
            put("recorded_at", System.currentTimeMillis())
            put("advertised_name", advertised)
            put("bluetooth_id", bluetoothAddress)
            put("bms_sn", sn.orEmpty())
            put("bms_hw_version", hw.orEmpty())
            put("bms_version", hw.orEmpty())
            put("source", if (BuildConfig.IS_SERVICE) "service" else "user")
            put("owner_name", owner?.fullName?.trim().orEmpty())
            put("owner_phone", owner?.phoneE164.orEmpty())
            put("owner_email", owner?.email?.trim().orEmpty())
            putNullable("voltage", battery.voltage)
            putNullable("current", battery.current)
            putNullable("soc", battery.soc)
            putNullable("remaining_ah", battery.remainingCapacityAh)
            putNullable("estimated_full_ah", battery.fullCapacityAh)
            putNullable("nominal_capacity_ah", nominal)
            battery.cellCount?.let { put("cell_count", it) }
            put("capacity_source", capacitySource(battery))
            putNullable("cell_diff_v", battery.cellDiffV)
            val cellVolts = battery.cells.map { it.voltage }
            putNullable("min_cell_v", cellVolts.minOrNull())
            putNullable("max_cell_v", cellVolts.maxOrNull())
            putNullable("min_temp", battery.minTemp)
            putNullable("max_temp", battery.maxTemp)
            putNullable("charge_mos", battery.chargeMosEnabled)
            putNullable("discharge_mos", battery.dischargeMosEnabled)
            val cells = JSONObject()
            battery.cells.forEach { cells.put(it.index.toString(), it.voltage) }
            put("cells", cells)
            val temps = JSONObject()
            battery.temperatures.forEachIndexed { i, t -> temps.put((i + 1).toString(), t) }
            put("temps", temps)
            val errors = JSONArray()
            battery.errors.forEach { errors.put(it) }
            put("errors", errors)
            // raw / events / hardware_family / bms_battery_code / config_snapshot:
            // require legacy Modbus / event buffers — intentionally omitted.
        }
    }

    /**
     * Legacy [MainActivity.dalyBluetoothDeviceId] style uid from MAC or DL name.
     *
     * @param address BLE MAC
     * @param bluetoothName saved/advertised name
     */
    fun resolveBmsUid(address: String, bluetoothName: String): String {
        val advertised = bluetoothName.trim()
        if (advertised.matches(Regex("^DL-[0-9A-Fa-f]+$"))) return advertised
        val macDigits = address.filter { it.isLetterOrDigit() }.uppercase()
        if (macDigits.isNotBlank()) return "DL-$macDigits"
        return advertised.ifBlank { address.ifBlank { "unknown_bms" } }
    }

    private fun nominalCapacityAh(battery: BatteryState): Double? {
        val est = battery.fullCapacityAh
        if (est != null && est > 0.0 && est < 2000.0) return est
        val rem = battery.remainingCapacityAh
        val soc = battery.soc
        if (rem != null && rem > 0.0 && soc != null && soc > 1.0 && soc <= 100.0) {
            return rem / (soc / 100.0)
        }
        return null
    }

    private fun capacitySource(battery: BatteryState): String {
        return when {
            battery.fullCapacityAh != null -> "runtime_remaining_ah_div_soc"
            battery.remainingCapacityAh != null && battery.soc != null ->
                "calculated_from_remaining_ah_and_soc"
            else -> "not_available"
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }
}
