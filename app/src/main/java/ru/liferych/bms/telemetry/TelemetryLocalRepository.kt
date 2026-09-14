package ru.liferych.bms.telemetry

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Локальный буфер telemetry + batch sync на backend.
 *
 * Flow: SAVE LOCAL (PENDING) → BATCH POST → ACK → SYNCED.
 * Sync не требует активного BLE.
 */
object TelemetryLocalRepository {
    private const val TAG = "BmsTelemetrySync"
    const val BATCH_SIZE = 100
    private const val SYNCED_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L

    private val io = Executors.newSingleThreadExecutor()

    private fun dao(context: Context) =
        BmsLocalDatabase.get(context).telemetryPointDao()

    /**
     * Назначение: сохранить telemetry point локально до отправки.
     * @param payload полный JSON как для /api/v1/telemetry (без обязательного api_key)
     * @return eventId
     */
    fun enqueue(context: Context, payload: JSONObject): String {
        val eventId = payload.optString("event_id").ifBlank {
            UUID.randomUUID().toString()
        }
        val recordedAt = payload.optLong("recorded_at", 0L).takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val bmsUid = payload.optString("bms_uid").trim()
        require(bmsUid.isNotBlank()) { "bms_uid required" }

        payload.put("event_id", eventId)
        payload.put("recorded_at", recordedAt)
        payload.remove("api_key")

        val entity = TelemetryPointEntity(
            eventId = eventId,
            bmsUid = bmsUid,
            recordedAt = recordedAt,
            payloadJson = payload.toString(),
            syncState = "PENDING",
        )
        val rowId = dao(context).insert(entity)
        Log.i(
            TAG,
            "TELEMETRY LOCAL SAVE battery=$bmsUid event=$eventId ts=$recordedAt row=$rowId",
        )
        return eventId
    }

    fun pendingCount(context: Context): Int = dao(context).countPending()

    /**
     * Назначение: отправить pending batch, пометить ACK.
     * @return число accepted + duplicates (считаются успешно обработанными)
     */
    fun syncOnce(context: Context): Int {
        val pending = dao(context).listPending(BATCH_SIZE)
        if (pending.isEmpty()) return 0

        val ids = pending.map { it.localId }
        dao(context).markState(ids, "SYNCING", attemptInc = 1, error = null)

        val points = JSONArray()
        for (row in pending) {
            try {
                points.put(JSONObject(row.payloadJson))
            } catch (e: Exception) {
                Log.w(TAG, "bad payload localId=${row.localId}: ${e.message}")
            }
        }
        if (points.length() == 0) {
            dao(context).markState(ids, "FAILED", attemptInc = 0, error = "empty_payload")
            return 0
        }

        Log.i(TAG, "SYNC BATCH count=${points.length()}")
        val body = JSONObject().put("points", points)
        val response = postBatch(body)
        if (response == null || !response.optBoolean("ok", false)) {
            val err = response?.optString("error").orEmpty().ifBlank { "network_error" }
            dao(context).markState(ids, "FAILED", attemptInc = 0, error = err)
            Log.w(TAG, "SYNC RESULT failed error=$err")
            return 0
        }

        val accepted = jsonStringList(response.optJSONArray("accepted"))
        val duplicates = jsonStringList(response.optJSONArray("duplicates"))
        val done = (accepted + duplicates).distinct()
        if (done.isNotEmpty()) {
            dao(context).markSynced(done, System.currentTimeMillis())
        }

        // Точки без ACK возвращаем в PENDING.
        val doneSet = done.toSet()
        val leftover = pending.filter { it.eventId !in doneSet }.map { it.localId }
        if (leftover.isNotEmpty()) {
            dao(context).markState(
                leftover,
                "FAILED",
                attemptInc = 0,
                error = "not_acknowledged",
            )
        }

        Log.i(
            TAG,
            "SYNC RESULT accepted=${accepted.size} duplicates=${duplicates.size} leftover=${leftover.size}",
        )

        val cutoff = System.currentTimeMillis() - SYNCED_RETENTION_MS
        val purged = dao(context).deleteSyncedOlderThan(cutoff)
        if (purged > 0) Log.i(TAG, "purged synced=$purged")

        return done.size
    }

    fun enqueueAsync(context: Context, payload: JSONObject, thenSync: Boolean = true) {
        val appContext = context.applicationContext
        io.execute {
            try {
                enqueue(appContext, payload)
                if (thenSync) {
                    TelemetrySyncScheduler.enqueueImmediate(appContext)
                    syncOnce(appContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "enqueueAsync failed: ${e.message}")
            }
        }
    }

    fun syncAsync(context: Context) {
        val appContext = context.applicationContext
        io.execute {
            try {
                syncOnce(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "syncAsync failed: ${e.message}")
            }
        }
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private fun postBatch(body: JSONObject): JSONObject? {
        return try {
            val conn = (URL(
                BmsApiConfig.BASE_URL.trimEnd('/') + "/api/v1/telemetry/batch"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-api-key", BmsApiConfig.API_KEY)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            conn.disconnect()
            if (text.isBlank()) null else JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "postBatch exception: ${e.message}")
            null
        }
    }
}
