package ru.liferych.bms.data.bms

/**
 * Daly factory SN Code decoder — same algorithm as legacy MainActivity.
 *
 * Source (HCI-proven in MainActivity): Modbus slave **0xD2**, holding registers
 * **0x0057–0x005D** (7 regs / up to 14 ASCII bytes). Does not invent registers.
 */
object DalyFactorySerial {
    const val SLAVE = 0xD2
    const val START = 0x0057
    const val END = 0x005D
    val ADDRESSES: IntRange = START..END

    /**
     * Decodes ASCII SN from a complete 0x0057–0x005D register map.
     *
     * @param registers address → raw unsigned 16-bit
     * @param swapBytes byte order within each register
     * @return trimmed ASCII or empty if incomplete / non-printable
     */
    fun decode(registers: Map<Int, Int>, swapBytes: Boolean): String {
        if (ADDRESSES.any { it !in registers }) return ""
        return buildString {
            for (addr in ADDRESSES) {
                val value = registers[addr] ?: return ""
                val hi = (value shr 8) and 0xFF
                val lo = value and 0xFF
                val first = if (swapBytes) lo else hi
                val second = if (swapBytes) hi else lo
                for (b in intArrayOf(first, second)) {
                    if (b == 0) return@buildString
                    if (b !in 32..126) return ""
                    append(b.toChar())
                }
            }
        }.trim()
    }

    /**
     * Candidate strings (normal + swapped byte order).
     */
    fun candidates(registers: Map<Int, Int>): List<String> {
        return listOf(
            decode(registers, swapBytes = false),
            decode(registers, swapBytes = true),
        ).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Legacy [MainActivity.isValidBmsSn]: length 8–20, not DL-*, letters+digits.
     */
    fun isValid(sn: String): Boolean {
        val value = sn.trim()
        if (value.length !in 8..20) return false
        if (value.startsWith("DL", ignoreCase = true)) return false
        return value.all { it.isLetterOrDigit() } &&
            value.any { it.isLetter() } &&
            value.any { it.isDigit() }
    }

    /**
     * Picks first valid candidate (prefer ones embedding HW version token).
     */
    fun pickValid(registers: Map<Int, Int>): String {
        val list = candidates(registers)
        val withVersion = list.firstOrNull { candidate ->
            isValid(candidate) && HW_VERSION_TOKENS.any {
                candidate.uppercase().contains(it)
            }
        }
        if (withVersion != null) return withVersion
        return list.firstOrNull { isValid(it) }.orEmpty()
    }

    private val HW_VERSION_TOKENS = listOf("R24TK", "R24TH", "R10K")
}
