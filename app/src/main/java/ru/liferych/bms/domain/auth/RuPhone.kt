package ru.liferych.bms.domain.auth

/**
 * Shared RU phone helpers matching legacy MainActivity extract/normalize/format.
 *
 * Display value (UI mask) and API value (E.164) are kept separate.
 */
object RuPhone {
    /**
     * Extracts national 10 digits (without country code).
     *
     * @param phone raw input (national mask, E.164, or mixed)
     * @return up to 10 national digits; empty if none
     */
    fun extractNationalDigits(phone: String): String {
        var digits = phone.filter { it.isDigit() }
        if (digits.startsWith("8") && digits.length == 11) {
            digits = digits.drop(1)
        } else if (digits.startsWith("7") && digits.length == 11) {
            digits = digits.drop(1)
        } else if (digits.startsWith("7") && digits.length > 10) {
            digits = digits.drop(1)
        }
        return digits.take(10)
    }

    /**
     * Builds E.164 for RU: +7XXXXXXXXXX.
     *
     * @param phone any phone string
     * @return E.164 or empty if national part is not 10 digits
     */
    fun toE164(phone: String): String {
        val national = extractNationalDigits(phone)
        return if (national.length == 10) "+7$national" else ""
    }

    /**
     * Formats national part only: 999 123-45-67.
     *
     * @param input raw national digits or masked text
     * @return national mask; empty stays empty (no auto «7»)
     */
    fun formatNationalMask(input: String): String {
        val n = extractNationalDigits(input)
        if (n.isEmpty()) return ""
        return buildString {
            append(n.take(3))
            if (n.length <= 3) return@buildString
            append(" ")
            append(n.drop(3).take(3))
            if (n.length <= 6) return@buildString
            append("-")
            append(n.drop(6).take(2))
            if (n.length <= 8) return@buildString
            append("-")
            append(n.drop(8).take(2))
        }
    }

    /**
     * Full display phone: +7 (999) 123-45-67.
     *
     * @param input phone string
     * @return display mask or empty
     */
    fun formatDisplay(input: String): String {
        val n = extractNationalDigits(input)
        if (n.isEmpty()) return ""
        return buildString {
            append("+7")
            append(" (")
            append(n.take(3))
            if (n.length < 3) return@buildString
            append(") ")
            append(n.drop(3).take(3))
            if (n.length <= 6) return@buildString
            append("-")
            append(n.drop(6).take(2))
            if (n.length <= 8) return@buildString
            append("-")
            append(n.drop(8).take(2))
        }
    }

    /**
     * Legacy validity: E.164 can be built.
     *
     * @param phone phone string
     * @return true when toE164 is non-blank
     */
    fun isValidRu(phone: String): Boolean = toE164(phone).isNotBlank()
}
