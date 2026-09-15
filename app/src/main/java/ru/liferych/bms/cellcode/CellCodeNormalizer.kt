package ru.liferych.bms.cellcode

/**
 * Нормализация сырого кода со сканера / ручного ввода.
 * Не меняет порядок символов; склеивает части, разделённые пробелами/CR/LF.
 */
object CellCodeNormalizer {
    /**
     * @param raw исходная строка со сканера
     * @return upper-case без пробелов и переносов
     */
    fun normalize(raw: String): String {
        return raw
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .replace("\t", "")
            .trim()
            .uppercase()
    }
}
