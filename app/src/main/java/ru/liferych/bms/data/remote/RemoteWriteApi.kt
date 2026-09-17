package ru.liferych.bms.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the existing server write-command queue.
 *
 * It preserves legacy endpoints, headers, payloads, and timeouts.
 */
class RemoteWriteApi(
    private val baseUrl: String = BmsApiConfig.BASE_URL,
    private val apiKeyProvider: () -> String = { BmsApiConfig.API_KEY },
) {
    /**
     * Fetches only the first pending command, matching legacy commands[0].
     *
     * @throws RemoteWriteApiException for transport or non-2xx failures.
     */
    suspend fun fetchFirstPending(bmsUid: String): RemoteWriteCommand? = withContext(Dispatchers.IO) {
        val encodedUid = encodePathSegment(bmsUid)
        val response = request(
            method = "GET",
            path = "/api/v1/batteries/$encodedUid/write-commands?status=pending&limit=20",
        )
        val commands = response.optJSONArray("commands") ?: return@withContext null
        if (commands.length() == 0) return@withContext null
        RemoteWriteCommand.fromJson(commands.optJSONObject(0))
            ?: throw RemoteWriteApiException("invalid_command_payload")
    }

    /**
     * Sends writing/done/failed status to the existing ACK endpoint.
     *
     * @return true when server accepted the ACK.
     */
    suspend fun acknowledge(
        bmsUid: String,
        commandId: Long,
        ack: RemoteWriteAck,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            // Kept for exact legacy compatibility; the header is authoritative.
            put("api_key", apiKeyProvider())
            put("status", ack.status)
            ack.actual?.takeIf { it.isFinite() }?.let { put("actual", it) }
            ack.error?.takeIf { it.isNotBlank() }?.let { put("error", it) }
        }
        val encodedUid = encodePathSegment(bmsUid)
        try {
            request(
                method = "POST",
                path = "/api/v1/batteries/$encodedUid/write-commands/$commandId/ack",
                body = body,
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "ack failed command=$commandId status=${ack.status}: ${error.message}")
            false
        }
    }

    /**
     * Performs one authenticated JSON request without logging secrets or body.
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (
            URL("${baseUrl.trimEnd('/')}/${path.trimStart('/')}").openConnection()
                as HttpURLConnection
            ).apply {
            requestMethod = method
            connectTimeout = LEGACY_HTTP_TIMEOUT_MS
            readTimeout = LEGACY_HTTP_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiKeyProvider())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw RemoteWriteApiException("HTTP $status")
            }
            if (text.isBlank()) {
                throw RemoteWriteApiException("empty_response")
            }
            JSONObject(text)
        } catch (error: RemoteWriteApiException) {
            throw error
        } catch (error: Exception) {
            throw RemoteWriteApiException(error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * URL-encodes one path segment using the same UTF-8 rules as legacy.
     */
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        private const val TAG = "RemoteWrite"
        private const val LEGACY_HTTP_TIMEOUT_MS = 8_000
    }
}

/**
 * Sanitized API failure; response bodies and credentials are intentionally omitted.
 */
class RemoteWriteApiException(
    message: String,
) : Exception(message)
