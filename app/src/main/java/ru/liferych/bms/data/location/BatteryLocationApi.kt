package ru.liferych.bms.data.location

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.domain.model.BatteryLocation
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CLIENT API for battery phone-location updates.
 */
class BatteryLocationApi(
    private val baseUrl: String = BmsApiConfig.BASE_URL,
    private val apiKeyProvider: () -> String = { BmsApiConfig.API_KEY },
) {
    /**
     * Posts one location sample for [location.bmsUid].
     *
     * @return true on HTTP 2xx with ok=true
     */
    suspend fun postLocation(location: BatteryLocation): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(location.bmsUid, Charsets.UTF_8.name())
        val body = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy_m", location.accuracyMeters?.toDouble())
            .put("recorded_at", location.recordedAt)
            .put("source", location.source)
        val conn = (URL(
            baseUrl.trimEnd('/') + "/api/v1/batteries/$encoded/location",
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", apiKeyProvider())
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (code !in 200..299) {
                Log.w(TAG, "location POST failed http=$code")
                return@withContext false
            }
            if (text.isBlank()) return@withContext true
            JSONObject(text).optBoolean("ok", true)
        } catch (error: Exception) {
            Log.w(TAG, "location POST exception: ${error.javaClass.simpleName}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        private const val TAG = "BatteryLocationApi"
    }
}
