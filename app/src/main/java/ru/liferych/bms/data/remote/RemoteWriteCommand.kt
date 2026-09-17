package ru.liferych.bms.data.remote

import org.json.JSONObject

/**
 * Existing server write-command contract.
 *
 * Register and raw value are authoritative server values and must not be
 * recalculated from a local template.
 */
data class RemoteWriteCommand(
    val id: Long,
    val bmsUid: String,
    val key: String,
    val label: String,
    val register: Int,
    val registerText: String,
    val unit: String,
    val value: Double,
    val rawValue: Int,
    val scale: Double,
    val offset: Double,
    val status: String,
    val actual: Double?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /**
         * Parses and validates one command returned by the existing server.
         *
         * @return command, or null for malformed/out-of-range input.
         */
        fun fromJson(json: JSONObject?): RemoteWriteCommand? {
            if (json == null) return null
            val id = json.optLong("id", 0L)
            val bmsUid = json.optString("bms_uid").trim()
            val key = json.optString("key").trim()
            val registerText = json.optString("register").trim()
            val rawValue = json.optInt("raw_value", Int.MIN_VALUE)
            val value = json.optDouble("value", Double.NaN)
            val scale = json.optDouble("scale", Double.NaN)
            val offset = json.optDouble("offset", 0.0)
            val register = registerText
                .removePrefix("0x")
                .removePrefix("0X")
                .toIntOrNull(16)

            if (id <= 0L || bmsUid.isBlank() || key.isBlank()) return null
            if (register == null || register !in 0..0xFFFF) return null
            if (rawValue !in 0..0xFFFF) return null
            if (!value.isFinite() || !scale.isFinite() || scale == 0.0) return null
            if (!offset.isFinite()) return null

            return RemoteWriteCommand(
                id = id,
                bmsUid = bmsUid,
                key = key,
                label = json.optString("label").ifBlank { key },
                register = register,
                registerText = registerText,
                unit = json.optString("unit"),
                value = value,
                rawValue = rawValue,
                scale = scale,
                offset = offset,
                status = json.optString("status"),
                actual = json.optFiniteDouble("actual"),
                error = json.optString("error").takeIf { it.isNotBlank() },
                createdAt = json.optLong("created_at", 0L),
                updatedAt = json.optLong("updated_at", 0L),
            )
        }
    }
}

/**
 * Reads a finite optional number without converting JSON null to zero.
 */
private fun JSONObject.optFiniteDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key, Double.NaN).takeIf { it.isFinite() }
}

/**
 * Result sent to the existing write-command ACK endpoint.
 */
data class RemoteWriteAck(
    val status: String,
    val actual: Double? = null,
    val error: String? = null,
)
