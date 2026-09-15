package ru.liferych.bms.cellcode

/**
 * Результат локального декодирования заводского кода элемента (Battery Coding Standard).
 * Не утверждает подлинность физической ячейки.
 */
data class CellQrDecodeResult(
    val rawCode: String,
    val normalizedCode: String,
    val recognition: CellCodeRecognition,
    val manufacturer: String? = null,
    val manufacturerCode: String? = null,
    val manufacturerWebsite: String? = null,
    val productType: String? = null,
    val productTypeCode: String? = null,
    val batteryType: String? = null,
    val batteryTypeCode: String? = null,
    val nominalCapacityAh: Double? = null,
    val nominalVoltageV: Double? = null,
    val productionDateIso: String? = null,
    val productionDateDisplay: String? = null,
    val productSeries: String? = null,
    val productAttribute: String? = null,
    val subsidiary: String? = null,
    val manufacturerAddress: String? = null,
    val recycled: Boolean? = null,
    val warnings: List<String> = emptyList()
)

enum class CellCodeRecognition {
    /** Структура и ключевые поля распознаны. */
    FULL,
    /** Длина/часть полей ок, но manufacturer/capacity/date могут отсутствовать. */
    PARTIAL,
    /** Формат не соответствует поддерживаемой структуре. */
    FAILED
}
