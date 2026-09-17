package ru.liferych.bms.domain.model

/**
 * Phone-derived location bound to a connected BMS.
 *
 * @property source always "phone" in v1 (BMS has no GPS)
 */
data class BatteryLocation(
    val bmsUid: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val recordedAt: Long,
    val source: String = SOURCE_PHONE,
) {
    companion object {
        const val SOURCE_PHONE = "phone"
    }
}
