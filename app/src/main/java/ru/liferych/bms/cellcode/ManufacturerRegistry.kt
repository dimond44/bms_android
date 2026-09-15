package ru.liferych.bms.cellcode

/**
 * Справочник кодов производителей (позиции 1–3 заводского кода).
 * Расширяется без правок UI.
 */
data class ManufacturerInfo(
    val code: String,
    val name: String,
    val website: String? = null
)

object ManufacturerRegistry {
    private val byCode: Map<String, ManufacturerInfo> = listOf(
        ManufacturerInfo("001", "CATL", "https://www.catl.com"),
        ManufacturerInfo("02Y", "EVE", "https://www.evebattery.com"),
        ManufacturerInfo("04Q", "EVE", "https://www.evebattery.com"),
        ManufacturerInfo("02K", "Great Power", "http://www.greatpower.net"),
        ManufacturerInfo("069", "GREE Altairnano/Yinlong"),
        ManufacturerInfo("081", "REPT", "https://www.chinarept.com"),
        ManufacturerInfo("08I", "REPT", "https://www.chinarept.com"),
        ManufacturerInfo("08B", "Lishen", "http://www.lishen.com.cn"),
        ManufacturerInfo("02C", "Lishen", "http://www.lishen.com.cn"),
        ManufacturerInfo("0AL", "Ganfeng", "http://www.ganfenglithium.com"),
        ManufacturerInfo("0B5", "CALB", "http://www.calb-tech.com"),
        ManufacturerInfo("OB5", "CALB", "http://www.calb-tech.com"),
        ManufacturerInfo("09U", "SVOLT"),
        ManufacturerInfo("0F4", "BYD"),
        ManufacturerInfo("07H", "Higee", "http://www.ihigee.com"),
        ManufacturerInfo("04U", "Narada Power"),
        ManufacturerInfo("00P", "Sunwoda"),
        ManufacturerInfo("0H9", "Tafel"),
        ManufacturerInfo("0IJ", "Hithium", "https://en.hithium.com"),
        ManufacturerInfo("01Y", "Narada"),
        ManufacturerInfo("03H", "Gotion", "https://www.gotion.com.cn")
    ).associateBy { it.code.uppercase() }

    /**
     * @param code 3-символьный код производителя
     * @return info или null, если mapping отсутствует
     */
    fun find(code: String): ManufacturerInfo? {
        return byCode[code.trim().uppercase()]
    }
}
