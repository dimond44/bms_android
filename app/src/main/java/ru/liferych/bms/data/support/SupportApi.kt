package ru.liferych.bms.data.support

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for legacy warranty support endpoints.
 *
 * Contracts (unchanged):
 * - POST /api/warranty_submit.php
 * - POST /api/warranty_list.php
 * - Header x-api-key + body api_key
 * - Submit uses application/json with media[].data_base64 (not classic multipart)
 */
class SupportApi(
    private val baseUrlProvider: () -> String = { BmsApiConfig.BASE_URL },
    private val apiKeyProvider: () -> String = { BmsApiConfig.API_KEY },
    private val executor: SupportRequestExecutor? = null,
) {
    /**
     * Fetches warranty list page for a BMS.
     *
     * @param bmsUid BMS identity
     * @param page 1-based page
     * @param limit page size
     * @return HTTP response or null on transport failure
     */
    fun listTickets(bmsUid: String, page: Int, limit: Int): SupportHttpResponse? {
        val body = JSONObject().apply {
            put("api_key", apiKeyProvider())
            put("bms_uid", bmsUid)
            put("all_by_bms", true)
            put("page", page)
            put("limit", limit)
        }
        return post(LIST_PATH, body, logTag = "LIST")
    }

    /**
     * Submits warranty create/update JSON payload (already contains media base64 if any).
     *
     * @param payload full request body
     * @return HTTP response or null
     */
    fun submitTicket(payload: JSONObject): SupportHttpResponse? {
        if (!payload.has("api_key")) {
            payload.put("api_key", apiKeyProvider())
        }
        return post(SUBMIT_PATH, payload, logTag = "CREATE", connectTimeout = 20_000, readTimeout = 60_000)
    }

    private fun post(
        path: String,
        body: JSONObject,
        logTag: String,
        connectTimeout: Int = 10_000,
        readTimeout: Int = 20_000,
    ): SupportHttpResponse? {
        executor?.let { return it.execute(path, body) }
        return try {
            val url = baseUrlProvider().trimEnd('/') + path
            if (BuildConfig.DEBUG) {
                val safeKeys = body.keys().asSequence().toList().filter { it != "api_key" }
                Log.i(LOG_TAG, "$logTag REQUEST path=$path body_keys=$safeKeys")
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", apiKeyProvider())
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            } finally {
                conn.disconnect()
            }
            if (BuildConfig.DEBUG) {
                // Do not log response body — may contain client_fio/phone/problem text.
                Log.i(LOG_TAG, "$logTag RESPONSE http=$code body_len=${raw.length}")
            }
            if (raw.isBlank() && code !in 200..299) {
                return SupportHttpResponse(code, JSONObject())
            }
            val json = try {
                JSONObject(raw.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            SupportHttpResponse(code, json)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$logTag NETWORK ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        const val SUBMIT_PATH = "/api/warranty_submit.php"
        const val LIST_PATH = "/api/warranty_list.php"
        const val PAGE_SIZE = 5
        private const val LOG_TAG = "SupportApi"
    }
}

/** Test double hook for [SupportApi]. */
fun interface SupportRequestExecutor {
    fun execute(path: String, body: JSONObject): SupportHttpResponse?
}

/**
 * @property httpCode status
 * @property json body
 */
data class SupportHttpResponse(
    val httpCode: Int,
    val json: JSONObject,
) {
    /** Convenience for list arrays. */
    fun requestsArray(): JSONArray = json.optJSONArray("requests") ?: JSONArray()
}
