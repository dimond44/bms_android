package ru.liferych.bms.telemetry

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.BuildConfig
import ru.liferych.bms.data.auth.AuthSessionStore
import ru.liferych.bms.data.bms.DalyData
import ru.liferych.bms.data.local.BmsIdentityStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime telemetry enqueue+sync used by [ru.liferych.bms.data.repository.DalyBmsRepository]
 * so FrontendActivity (Compose) uploads without MainActivity.
 *
 * Semantics match legacy [ru.liferych.bms.MainActivity.onPollCompleted] /
 * [uploadCurrentData]: after poll cycle (0x98), rate-limited enqueue to Room,
 * then syncOnce + WorkManager kick. No offline event / heartbeat.
 */
class RuntimeTelemetryUploader(
    context: Context,
    private val identityStore: BmsIdentityStore,
    private val authSessionStore: AuthSessionStore,
) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    private val lastUploadAt = AtomicLong(0L)
    private val pendingFirstUpload = AtomicBoolean(false)

    /**
     * Call when GATT characteristics are ready (legacy pendingFirstTelemetryUpload=true).
     */
    fun markFirstUploadPending() {
        pendingFirstUpload.set(true)
        Log.i(TAG, "Telemetry first-upload armed")
    }

    /**
     * Call on GATT disconnect — drop first-upload latch; do not enqueue offline.
     */
    fun onDisconnected() {
        pendingFirstUpload.set(false)
        Log.i(TAG, "Telemetry upload disarmed (disconnected)")
    }

    /**
     * Legacy onPollCompleted trigger (end of 0x90–0x98 cycle).
     *
     * @param data live Daly buffer
     * @param bluetoothAddress selected MAC
     * @param bluetoothName advertised / saved BLE name
     * @param liveHwVersion optional ASCII from cmd 0x63 (preferred over cache)
     */
    fun onPollCycleCompleted(
        data: DalyData,
        bluetoothAddress: String?,
        bluetoothName: String,
        liveHwVersion: String = "",
    ) {
        val forceFirst = pendingFirstUpload.getAndSet(false)
        upload(data, bluetoothAddress, bluetoothName, force = forceFirst, liveHwVersion = liveHwVersion)
    }

    /**
     * Enqueue + sync once (legacy uploadCurrentData).
     *
     * @param force bypass interval / empty voltage+soc guard (first upload)
     */
    fun upload(
        data: DalyData,
        bluetoothAddress: String?,
        bluetoothName: String,
        force: Boolean,
        liveHwVersion: String = "",
    ) {
        val uid = resolveBmsUid(bluetoothAddress, bluetoothName)
        if (!hasStableIdentity(uid, bluetoothAddress)) {
            Log.w(TAG, "Telemetry upload skipped: unstable bms_uid")
            return
        }
        if (data.voltage == null && data.soc == null && !force) return
        val now = System.currentTimeMillis()
        if (!force && now - lastUploadAt.get() < UPLOAD_INTERVAL_MS) return
        if (!uploading.compareAndSet(false, true)) return
        lastUploadAt.set(now)

        io.execute {
            try {
                val payload = buildPayload(
                    data = data,
                    bmsUid = uid,
                    bluetoothAddress = bluetoothAddress.orEmpty(),
                    bluetoothName = bluetoothName,
                    liveHwVersion = liveHwVersion,
                )
                Log.i(TAG, "Telemetry queued bms_uid=$uid event=${payload.optString("event_id")}")
                TelemetryLocalRepository.enqueue(appContext, payload)
                Log.i(TAG, "Telemetry upload started")
                val done = TelemetryLocalRepository.syncOnce(appContext)
                val pending = TelemetryLocalRepository.pendingCount(appContext)
                TelemetrySyncScheduler.enqueueImmediate(appContext)
                if (done > 0) {
                    Log.i(TAG, "Telemetry upload success ack=$done pending=$pending")
                } else if (pending > 0) {
                    Log.w(TAG, "Telemetry upload pending_local=$pending (sync deferred)")
                } else {
                    Log.i(TAG, "Telemetry ACK queue empty after sync")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telemetry upload failed: ${e.javaClass.simpleName}")
            } finally {
                uploading.set(false)
            }
        }
    }

    /**
     * Same identity rules as MainActivity.dalyBluetoothDeviceId / bmsUid.
     */
    fun resolveBmsUid(bluetoothAddress: String?, bluetoothName: String): String =
        TelemetryBmsUid.resolve(bluetoothAddress, bluetoothName)

    private fun hasStableIdentity(uid: String, address: String?): Boolean =
        TelemetryBmsUid.isStable(uid, address)

    private fun buildPayload(
        data: DalyData,
        bmsUid: String,
        bluetoothAddress: String,
        bluetoothName: String,
        liveHwVersion: String,
    ): JSONObject {
        val advertised = if (bluetoothName.matches(Regex("^DL-[0-9A-Fa-f]+$"))) {
            bluetoothName
        } else {
            bmsUid
        }
        val factorySn = identityStore.displayFactorySerial(
            identityStore.cachedFactorySerial(bluetoothAddress),
        )
        val hw = liveHwVersion.trim().ifBlank {
            identityStore.cachedHwVersion(bluetoothAddress).orEmpty()
        }
        if (hw.isNotBlank() && bluetoothAddress.isNotBlank()) {
            identityStore.saveHwVersion(bluetoothAddress, hw)
        }
        val owner = authSessionStore.readActiveProfile()
        val nominal = nominalCapacityAh(data)
        return JSONObject().apply {
            put("api_key", BmsApiConfig.API_KEY)
            put("bms_uid", bmsUid)
            put("bluetooth_name", advertised)
            put("bluetooth_address", bluetoothAddress)
            put("event_id", UUID.randomUUID().toString())
            put("recorded_at", System.currentTimeMillis())
            put("advertised_name", advertised)
            put("bluetooth_id", bluetoothAddress)
            put("bms_sn", factorySn)
            put("bms_battery_code", "")
            put("bms_hw_version", hw)
            put("bms_version", hw)
            put("source", if (BuildConfig.IS_SERVICE) "service" else "user")
            put("owner_name", owner?.fullName.orEmpty())
            put("owner_phone", owner?.phoneE164.orEmpty())
            put("owner_email", owner?.email.orEmpty())
            putNullable("voltage", data.voltage)
            putNullable("current", data.current)
            putNullable("soc", data.soc)
            putNullable("remaining_ah", data.remainingAh)
            putNullable("estimated_full_ah", data.estimatedFullAh)
            putNullable("nominal_capacity_ah", nominal)
            data.cellCount?.let { put("cell_count", it) }
            put("capacity_source", capacitySource(data))
            putNullable("cell_diff_v", data.cellDiffV)
            putNullable("min_cell_v", data.minCellV)
            putNullable("max_cell_v", data.maxCellV)
            putNullable("min_temp", data.minTemp)
            putNullable("max_temp", data.maxTemp)
            putNullable("charge_mos", data.chargeMos)
            putNullable("discharge_mos", data.dischargeMos)
            val cells = JSONObject()
            for ((k, v) in data.cells) cells.put(k.toString(), v)
            put("cells", cells)
            val temps = JSONObject()
            for ((k, v) in data.temps) temps.put(k.toString(), v)
            put("temps", temps)
            val errors = JSONArray()
            for (e in data.errors) errors.put(e)
            put("errors", errors)
            val raw = JSONObject()
            for ((k, v) in data.raw) raw.put(k, v)
            put("raw", raw)
            // events / historical_raw: MainActivity-only buffers — omit when absent.
            put("events", JSONArray())
        }
    }

    private fun nominalCapacityAh(data: DalyData): Double? {
        val est = data.estimatedFullAh
        if (est != null && est > 0.0 && est < 2000.0) return est
        val rem = data.remainingAh
        val soc = data.soc
        if (rem != null && rem > 0.0 && soc != null && soc > 1.0 && soc <= 100.0) {
            return rem / (soc / 100.0)
        }
        return null
    }

    private fun capacitySource(data: DalyData): String {
        return when {
            data.estimatedFullAh != null -> "runtime_remaining_ah_div_soc"
            data.remainingAh != null && data.soc != null -> "calculated_from_remaining_ah_and_soc"
            else -> "not_available"
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    companion object {
        private const val TAG = "BmsTelemetryRuntime"
        /** Legacy MainActivity UPLOAD_INTERVAL_MS. */
        const val UPLOAD_INTERVAL_MS = 15_000L
    }
}
