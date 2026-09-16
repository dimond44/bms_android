package ru.liferych.bms.data.bms

/**
 * Mutable runtime buffer for Daly UART/BLE telemetry (A5 frames 0x90–0x98).
 * Kept mutable to match legacy MainActivity behaviour during the extraction stage.
 */
data class DalyData(
    var voltage: Double? = null,
    var current: Double? = null,
    var soc: Double? = null,
    var remainingAh: Double? = null,
    var estimatedFullAh: Double? = null,
    var maxCellV: Double? = null,
    var maxCellNo: Int? = null,
    var minCellV: Double? = null,
    var minCellNo: Int? = null,
    var cellDiffV: Double? = null,
    var maxTemp: Int? = null,
    var minTemp: Int? = null,
    var chargeMos: Boolean? = null,
    var dischargeMos: Boolean? = null,
    var cellCount: Int? = null,
    var tempCount: Int? = null,
    var chargerConnected: Boolean? = null,
    var loadConnected: Boolean? = null,
    var cycles: Int? = null,
    var lastUpdatedAt: Long = 0L,
    val cells: MutableMap<Int, Double> = sortedMapOf(),
    val temps: MutableMap<Int, Int> = sortedMapOf(),
    val balancingCells: MutableSet<Int> = sortedSetOf(),
    val raw: MutableMap<String, String> = linkedMapOf(),
    val errors: MutableList<String> = mutableListOf(),
)
