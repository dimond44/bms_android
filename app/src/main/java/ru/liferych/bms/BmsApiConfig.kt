package ru.liferych.bms

/**
 * Единая конфигурация BMS API.
 *
 * local.properties (не в git):
 *   BMS_API_KEY=…              — DEVICE ingest
 *   BMS_EXECUTOR_API_KEY=…     — EXECUTOR (GET pending + ACK only)
 *   aliases: DEVICE_API_KEY, EXECUTOR_API_KEY, BMS_SERVICE_API_KEY, SERVICE_API_KEY
 *
 * ADMIN_API_KEY must NEVER appear in the APK.
 */
object BmsApiConfig {
    const val BASE_URL = "https://5.3.87.2"

    /** DEVICE ingest key. */
    val API_KEY: String
        get() = BuildConfig.BMS_API_KEY

    /**
     * EXECUTOR key for remote-write poll/ACK only.
     * No fallback to DEVICE — missing executor key means remote write cannot auth.
     */
    val EXECUTOR_API_KEY: String
        get() = BuildConfig.BMS_EXECUTOR_API_KEY

    /** @deprecated use [EXECUTOR_API_KEY]; kept for call-site compatibility. */
    val SERVICE_API_KEY: String
        get() = EXECUTOR_API_KEY
}
