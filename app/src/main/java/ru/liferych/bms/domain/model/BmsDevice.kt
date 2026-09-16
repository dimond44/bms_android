package ru.liferych.bms.domain.model

/**
 * BLE device discovered during scan (legacy list item).
 */
data class BmsDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)
