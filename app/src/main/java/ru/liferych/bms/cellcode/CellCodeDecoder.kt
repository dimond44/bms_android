package ru.liferych.bms.cellcode

/**
 * Локальный decoder заводских кодов LiFePO4 / China Battery Coding Standard.
 * Не зависит от gobelpower.com и не утверждает подлинность ячейки.
 *
 * Структура 24-символьного кода (1-based):
 * 1–3 manufacturer, 4 product type, 5 chemistry, 6–7 specification,
 * 8–14 traceback, 15–17 production date, 18–24 serial.
 */
object CellCodeDecoder {

    private val productTypesRu = mapOf(
        "C" to "Аккумуляторный элемент",
        "P" to "Батарея (Pack)",
        "M" to "Батарейный модуль"
    )

    private val batteryTypes = mapOf(
        "A" to ("NiMH" to 1.2),
        "B" to ("LiFePO4" to 3.2),
        "C" to ("LiMn2O4" to 3.0),
        "D" to ("LiCoO2" to 3.7),
        "E" to ("NMC / Ternary" to 3.7),
        "F" to ("Super-capacitor" to null),
        "G" to ("LTO" to 2.3),
        "Z" to ("Другой" to null)
    )

    /**
     * Декодирует код элемента.
     *
     * @param raw исходный код (сканер или ручной ввод)
     * @return результат без выдуманных значений
     */
    fun decode(raw: String): CellQrDecodeResult {
        val rawCode = raw
        val normalized = CellCodeNormalizer.normalize(raw)
        if (normalized.isBlank()) {
            return CellQrDecodeResult(
                rawCode = rawCode,
                normalizedCode = normalized,
                recognition = CellCodeRecognition.FAILED,
                warnings = listOf("Пустой код")
            )
        }

        val lengthOk = normalized.length == 19 ||
            normalized.length == 24 ||
            normalized.length == 26
        if (!lengthOk || normalized.length < 5) {
            return CellQrDecodeResult(
                rawCode = rawCode,
                normalizedCode = normalized,
                recognition = CellCodeRecognition.FAILED,
                warnings = listOf(
                    "Не удалось определить формат кода (ожидается 24 или 19 символов)"
                )
            )
        }

        val manufacturerCode = normalized.substring(0, 3)
        val productTypeCode = normalized.substring(3, 4)
        val batteryTypeCode = normalized.substring(4, 5)
        val recycled = when (normalized.length) {
            19 -> true
            24, 26 -> false
            else -> null
        }

        val manufacturerInfo = ManufacturerRegistry.find(manufacturerCode)
        val productType = productTypesRu[productTypeCode]
        val batteryPair = batteryTypes[batteryTypeCode]
        val batteryType = batteryPair?.first
        val voltage = batteryPair?.second

        val spec = resolveSpecification(manufacturerInfo?.name, manufacturerCode, normalized)
        val date = if (normalized.length >= 17) {
            decodeProductionDate(normalized.substring(14, 17))
        } else {
            null
        }

        val warnings = mutableListOf<String>()
        if (productType == null) {
            warnings += "Тип продукта (4-й символ) не C/P/M — формат может быть неверным"
        }
        if (manufacturerInfo == null) {
            warnings += "Производитель по коду $manufacturerCode в справочнике не найден"
        }
        if (spec == null) {
            warnings += "Ёмкость/серия для данного manufacturer+spec не определены"
        }

        val recognition = when {
            productType != null && batteryType != null &&
                (manufacturerInfo != null || date != null || spec != null) -> {
                if (manufacturerInfo != null && (spec != null || date != null)) {
                    CellCodeRecognition.FULL
                } else {
                    CellCodeRecognition.PARTIAL
                }
            }
            productType != null || batteryType != null || date != null ->
                CellCodeRecognition.PARTIAL
            else -> CellCodeRecognition.FAILED
        }

        return CellQrDecodeResult(
            rawCode = rawCode,
            normalizedCode = normalized,
            recognition = recognition,
            manufacturer = manufacturerInfo?.name,
            manufacturerCode = manufacturerCode,
            manufacturerWebsite = manufacturerInfo?.website,
            productType = productType,
            productTypeCode = productTypeCode,
            batteryType = batteryType,
            batteryTypeCode = batteryTypeCode,
            nominalCapacityAh = spec?.capacityAh,
            nominalVoltageV = if (productTypeCode == "C") voltage else null,
            productionDateIso = date?.first,
            productionDateDisplay = date?.second,
            productSeries = spec?.series,
            productAttribute = null,
            subsidiary = null,
            manufacturerAddress = null,
            recycled = recycled,
            warnings = warnings
        )
    }

    private data class SpecInfo(val series: String, val capacityAh: Double)

    fun decodeProductionDate(code3: String): Pair<String, String>? {
        if (code3.length != 3) return null
        val yearCode = code3[0]
        val monthCode = code3[1]
        val dayCode = code3[2]

        val year = when {
            yearCode.isDigit() && yearCode != '0' -> 2010 + (yearCode - '0')
            yearCode in 'A'..'Z' -> 2020 + (yearCode - 'A')
            else -> return null
        }

        val month = when {
            monthCode.isDigit() && monthCode != '0' -> monthCode - '0'
            monthCode in 'A'..'C' -> 10 + (monthCode - 'A')
            else -> return null
        }
        if (month !in 1..12) return null

        val dayLookup = mapOf(
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5,
            '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            'A' to 10, 'B' to 11, 'C' to 12, 'D' to 13, 'E' to 14,
            'F' to 15, 'G' to 16, 'H' to 17, 'J' to 18, 'K' to 19,
            'L' to 20, 'M' to 21, 'N' to 22, 'P' to 23, 'R' to 24,
            'S' to 25, 'T' to 26, 'U' to 27, 'V' to 28, 'X' to 29,
            'Y' to 30, 'Z' to 31
        )
        val day = dayLookup[dayCode] ?: return null
        if (day !in 1..31) return null

        val iso = "%04d-%02d-%02d".format(year, month, day)
        val display = "%02d.%02d.%04d".format(day, month, year)
        return iso to display
    }

    private fun resolveSpecification(
        manufacturerName: String?,
        manufacturerCode: String,
        normalized: String
    ): SpecInfo? {
        if (normalized.length < 7) return null
        val name = manufacturerName.orEmpty()
        val specCode = if (name == "Gotion") {
            if (normalized.length < 8) return null
            normalized.substring(6, 8)
        } else {
            normalized.substring(5, 7)
        }

        val table = when {
            name == "Gotion" -> gotionSpecs
            name.startsWith("GREE") -> greeSpecs
            name == "SVOLT" -> svoltSpecs
            name == "BYD" -> bydSpecs
            name == "REPT" -> reptSpecs
            name == "EVE" || manufacturerCode == "04Q" || manufacturerCode == "02Y" ->
                eveAndCommonSpecs
            name == "CATL" || manufacturerCode == "001" -> catlAndCommonSpecs
            else -> commonSpecs
        }
        return table[specCode]
    }

    /** Общие spec-коды (позиции 6–7) для неизвестных/смешанных производителей. */
    private val commonSpecs: Map<String, SpecInfo> = mapOf(
        "20" to SpecInfo("202AH", 202.0),
        "2O" to SpecInfo("202AH", 202.0),
        "21" to SpecInfo("71H3L7", 280.0),
        "22" to SpecInfo("173AH", 173.0),
        "24" to SpecInfo("302AH", 302.0),
        "2W" to SpecInfo("71H3L7", 280.0),
        "31" to SpecInfo("280AH", 280.0),
        "3I" to SpecInfo("280AH", 280.0),
        "32" to SpecInfo("125AH", 125.0),
        "52" to SpecInfo("114AH", 114.0),
        "15" to SpecInfo("50AH", 50.0),
        "64" to SpecInfo("LF90K", 90.0),
        "65" to SpecInfo("LF105", 105.0),
        "66" to SpecInfo("LF280", 280.0),
        "68" to SpecInfo("LF50", 50.0),
        "6C" to SpecInfo("LF100L", 100.0),
        "6D" to SpecInfo("LF100MA", 100.0),
        "71" to SpecInfo("LF280N", 280.0),
        "7I" to SpecInfo("LF280N", 280.0),
        "72" to SpecInfo("LF230", 230.0),
        "73" to SpecInfo("LF304", 304.0),
        "75" to SpecInfo("LF173", 173.0),
        "76" to SpecInfo("LF280K", 280.0),
        "E6" to SpecInfo("LF22K", 22.0)
    )

    /** EVE-ориентированные + общие spec-коды. */
    private val eveAndCommonSpecs: Map<String, SpecInfo> = commonSpecs

    private val catlAndCommonSpecs: Map<String, SpecInfo> = commonSpecs

    private val gotionSpecs = mapOf(
        "14" to SpecInfo("52AH", 52.0),
        "0R" to SpecInfo("52AH", 52.0),
        "16" to SpecInfo("GT102", 102.0),
        "09" to SpecInfo("105AH", 105.0),
        "1B" to SpecInfo("150AH", 150.0),
        "23" to SpecInfo("GT-340", 340.0)
    )

    private val greeSpecs = mapOf(
        "A2" to SpecInfo("66160", 40.0),
        "A3" to SpecInfo("66160", 45.0),
        "A4" to SpecInfo("32140", 9.0)
    )

    private val svoltSpecs = mapOf(
        "20" to SpecInfo("CL01", 184.0),
        "10" to SpecInfo("CE01", 104.0)
    )

    private val bydSpecs = mapOf(
        "A3" to SpecInfo("102AH", 102.0)
    )

    private val reptSpecs = mapOf(
        "05" to SpecInfo("205AH", 205.0),
        "O5" to SpecInfo("205AH", 205.0),
        "26" to SpecInfo("CB71173200EA", 280.0),
        "29" to SpecInfo("230AH", 230.0),
        "40" to SpecInfo("CB71173204EB", 280.0),
        "25" to SpecInfo("135AH", 135.0),
        "54" to SpecInfo("142AH", 142.0),
        "03" to SpecInfo("CB3914895EA-50A", 50.0),
        "09" to SpecInfo("155AH", 155.0)
    )
}
