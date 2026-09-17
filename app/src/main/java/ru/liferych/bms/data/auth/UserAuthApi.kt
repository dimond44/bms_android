package ru.liferych.bms.data.auth

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.BuildConfig
import ru.liferych.bms.data.local.SavedBattery
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.auth.UserProfile
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for legacy user auth/profile endpoints.
 *
 * Contracts (unchanged):
 * - POST /api/v1/users/login|register|profile
 * - POST /api/v1/users/avatar (JSON: api_key, phone, image_base64)
 * - Header x-api-key + body api_key
 * - Body phone (+ name/email/birth when provided)
 *
 * Does not log api_key, Authorization, full phone, or image_base64.
 */
class UserAuthApi(
    private val baseUrlProvider: () -> String = { BmsApiConfig.BASE_URL },
    private val apiKeyProvider: () -> String = { BmsApiConfig.API_KEY },
    private val requestExecutor: UserAuthRequestExecutor? = null,
    private val avatarExecutor: UserAuthAvatarExecutor? = null,
) {
    /**
     * POST login/register/profile.
     *
     * @param path absolute API path starting with /
     * @param phone E.164 phone
     * @param name optional name (register/profile)
     * @param email optional email
     * @param birth optional birth
     * @return parsed response or null on transport failure
     */
    fun request(
        path: String,
        phone: String,
        name: String? = null,
        email: String? = null,
        birth: String? = null,
    ): UserAuthHttpResponse? {
        requestExecutor?.let { return it.execute(path, phone, name, email, birth) }
        return executeHttp(path, phone, name, email, birth)
    }

    private fun executeHttp(
        path: String,
        phone: String,
        name: String?,
        email: String?,
        birth: String?,
    ): UserAuthHttpResponse? {
        return try {
            val body = JSONObject().apply {
                put("api_key", apiKeyProvider())
                put("phone", phone)
                if (name != null) put("name", name)
                if (email != null) put("email", email)
                if (birth != null) put("birth", birth)
            }
            val url = baseUrlProvider().trimEnd('/') + path
            if (BuildConfig.DEBUG) {
                val safeKeys = body.keys().asSequence().toList().filter { it != "api_key" }
                Log.i(
                    LOG_TAG,
                    "REQUEST method=POST path=$path phone=${maskPhone(phone)} body_keys=$safeKeys",
                )
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
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
                // Do not log response body — may contain phone/email/name.
                Log.i(LOG_TAG, "RESPONSE path=$path http=$code body_len=${raw.length}")
            }
            if (raw.isBlank()) return null
            UserAuthHttpResponse(httpCode = code, json = JSONObject(raw))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "NETWORK path=$path error=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * POST /api/v1/users/avatar — legacy JSON body with Base64 JPEG.
     *
     * @param phone E.164 phone
     * @param jpegBytes compressed JPEG bytes (not logged)
     * @return response or null on transport failure
     *
     * Security: does not log image_base64 / api_key.
     */
    fun uploadAvatar(phone: String, jpegBytes: ByteArray): UserAuthHttpResponse? {
        avatarExecutor?.let { return it.execute(phone, jpegBytes) }
        return executeAvatarHttp(phone, jpegBytes)
    }

    private fun executeAvatarHttp(phone: String, jpegBytes: ByteArray): UserAuthHttpResponse? {
        return try {
            val body = JSONObject().apply {
                put("api_key", apiKeyProvider())
                put("phone", phone)
                put("image_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            }
            val url = baseUrlProvider().trimEnd('/') + AVATAR_PATH
            if (BuildConfig.DEBUG) {
                Log.i(
                    LOG_TAG,
                    "REQUEST method=POST path=$AVATAR_PATH phone=${maskPhone(phone)} jpeg_bytes=${jpegBytes.size}",
                )
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
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
                Log.i(LOG_TAG, "RESPONSE path=$AVATAR_PATH http=$code body_len=${raw.length}")
            }
            if (raw.isBlank()) return null
            UserAuthHttpResponse(httpCode = code, json = JSONObject(raw))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "NETWORK path=$AVATAR_PATH error=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Downloads avatar image bytes (legacy restoreProfileAvatarAfterLogin GET).
     *
     * @param avatarUrl absolute or server-relative URL
     * @return JPEG bytes or null
     */
    fun downloadAvatarBytes(avatarUrl: String): ByteArray? {
        if (avatarUrl.isBlank()) return null
        return try {
            val absolute = if (avatarUrl.startsWith("http", ignoreCase = true)) {
                avatarUrl
            } else {
                baseUrlProvider().trimEnd('/') +
                    if (avatarUrl.startsWith("/")) avatarUrl else "/$avatarUrl"
            }
            val conn = (URL(absolute).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Accept", "image/jpeg,image/*")
                setRequestProperty("x-api-key", apiKeyProvider())
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                return null
            }
            bytes
        } catch (e: Exception) {
            Log.w(LOG_TAG, "AVATAR download error=${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        const val LOGIN_PATH = "/api/v1/users/login"
        const val REGISTER_PATH = "/api/v1/users/register"
        const val PROFILE_PATH = "/api/v1/users/profile"
        const val AVATAR_PATH = "/api/v1/users/avatar"

        private const val LOG_TAG = "UserAuthApi"

        /**
         * Masks phone for logs: +7999***4567.
         *
         * @param phone raw phone
         * @return masked string
         */
        fun maskPhone(phone: String): String {
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 6) return "***"
            return "+${digits.take(4)}***${digits.takeLast(4)}"
        }
    }
}

/**
 * Injectable transport for [UserAuthApi] (unit tests / fakes).
 */
fun interface UserAuthRequestExecutor {
    /**
     * Executes one auth/profile POST.
     *
     * @return response or null on transport failure
     */
    fun execute(
        path: String,
        phone: String,
        name: String?,
        email: String?,
        birth: String?,
    ): UserAuthHttpResponse?
}

/**
 * Injectable transport for avatar upload (unit tests / fakes).
 */
fun interface UserAuthAvatarExecutor {
    /**
     * Executes POST /api/v1/users/avatar.
     *
     * @param phone E.164
     * @param jpegBytes JPEG payload (tests must not log)
     * @return response or null on transport failure
     */
    fun execute(phone: String, jpegBytes: ByteArray): UserAuthHttpResponse?
}

/**
 * Raw HTTP response wrapper (no secrets).
 *
 * @property httpCode status code
 * @property json response JSON
 */
data class UserAuthHttpResponse(
    val httpCode: Int,
    val json: JSONObject,
)

/**
 * Maps server `user` JSON + fallbacks to [UserProfile].
 *
 * @param userJson optJSONObject("user") or null
 * @param fallbackPhone E.164 used when server omits phone
 * @param fallbackName name used when server omits name
 * @return domain profile
 */
fun mapUserJsonToProfile(
    userJson: JSONObject?,
    fallbackPhone: String,
    fallbackName: String,
): UserProfile {
    val remotePhone = RuPhone.toE164(
        userJson?.optString("phone").orEmpty().ifBlank { fallbackPhone },
    ).ifBlank { fallbackPhone }
    val remoteName = userJson?.optString("name").orEmpty().ifBlank { fallbackName }
    return UserProfile(
        fullName = remoteName,
        phoneE164 = remotePhone,
        email = userJson?.optString("email").orEmpty(),
        birthDate = userJson?.optString("birth").orEmpty(),
        avatarUrl = userJson?.optString("avatar_url").orEmpty().trim(),
        avatarLocalPath = "",
    )
}

/**
 * Maps server `batteries` array to local SavedBattery list (legacy mapServerBatteriesToSaved).
 *
 * @param arr JSONArray or null
 * @return deduplicated list sorted by lastSeenAt desc
 */
fun mapServerBatteriesToSaved(arr: JSONArray?): List<SavedBattery> {
    if (arr == null) return emptyList()
    val out = mutableListOf<SavedBattery>()
    val seen = HashSet<String>()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val address = item.optString("bluetooth_address").trim()
            .ifBlank { item.optString("bluetooth_id").trim() }
            .ifBlank { item.optString("bms_uid").trim() }
        if (address.isBlank()) continue
        val key = address.uppercase()
        if (!seen.add(key)) continue
        val name = item.optString("advertised_name").ifBlank {
            item.optString("bluetooth_name")
        }
        out += SavedBattery(
            address = address,
            bluetoothName = name,
            customName = "",
            soc = if (item.has("soc") && !item.isNull("soc")) item.optDouble("soc") else null,
            capacityAh = if (item.has("capacity_ah") && !item.isNull("capacity_ah")) {
                item.optDouble("capacity_ah")
            } else {
                null
            },
            lastSeenAt = item.optLong("last_seen_at", 0L),
        )
    }
    return out.sortedByDescending { it.lastSeenAt }
}

/**
 * User-facing register failure message (legacy registerFailureMessage).
 *
 * @param httpCode HTTP status
 * @param error server error field
 * @return safe message
 */
fun registerFailureMessage(httpCode: Int, error: String): String {
    return when {
        error == "invalid_phone" || (httpCode == 400 && error.contains("phone")) ->
            "Некорректный номер телефона"
        error == "name_required" ->
            "Укажите ФИО для регистрации"
        error == "unauthorized" || httpCode == 401 ->
            "Ошибка авторизации приложения на сервере"
        error == "not_found" || httpCode == 404 ->
            "Сервер не поддерживает регистрацию (endpoint недоступен). Обновите backend."
        httpCode in 500..599 ->
            "Ошибка сервера. Попробуйте позже."
        error.isNotBlank() ->
            "Не удалось зарегистрироваться ($error)"
        else ->
            "Не удалось зарегистрироваться (HTTP $httpCode)"
    }
}
