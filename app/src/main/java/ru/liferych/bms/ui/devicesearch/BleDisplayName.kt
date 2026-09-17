package ru.liferych.bms.ui.devicesearch

/**
 * Display-only cleanup of BLE advertised names for Compose UI.
 *
 * Source of the «i�» / black-diamond artifact:
 * some Daly AD records embed a C-string NUL (`\u0000`) after the real ID,
 * followed by garbage bytes. Android decodes those as Latin `i` + U+FFFD.
 * Legacy MainActivity strips this via [sanitizeBleText]; Compose previously
 * showed the raw [ru.liferych.bms.domain.model.BmsDevice.name] unchanged.
 *
 * This function mirrors legacy behaviour for UI only — raw BLE names used for
 * connect / storage must not be mutated by callers.
 *
 * Rules:
 * 1. Truncate at the first NUL (advertised C-string terminator).
 * 2. Keep printable ASCII (32..126) and Unicode letters/digits (Cyrillic etc.).
 * 3. Drop controls, U+FFFD, and other non-printable symbols.
 * 4. If U+FFFD was present, also drop a trailing ` i` junk pair often left after step 3.
 *
 * @param raw advertised BLE name (may contain NUL / replacement chars)
 * @return cleaned label safe for user-facing Text; empty if nothing remains
 */
fun sanitizeBleDisplayName(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    val hadReplacement = raw.any { it == '\uFFFD' }
    val cut = raw.indexOf('\u0000')
    val head = if (cut >= 0) raw.substring(0, cut) else raw
    val cleaned = buildString(head.length) {
        for (ch in head) {
            val code = ch.code
            if (code in 32..126 || ch.isLetterOrDigit()) {
                append(ch)
            }
        }
    }.trim()
    // Pattern seen with AD garbage: "DL-… i" + U+FFFD → after drop FFFD still "… i"
    if (hadReplacement && cleaned.endsWith(" i")) {
        return cleaned.removeSuffix(" i").trimEnd()
    }
    return cleaned
}

/**
 * Shared UI title for BMS cards / Dashboard «Имя устройства».
 *
 * Priority: user alias (as-is) → sanitized BLE name → [fallback].
 * Does not mutate raw BLE / storage fields.
 *
 * @param customName user-assigned alias; blank means unused
 * @param bluetoothName raw advertised BLE name (may contain NUL / U+FFFD)
 * @param fallback used when alias and cleaned name are empty (e.g. MAC or `--`)
 * @return label for user-facing Text
 */
fun deviceUiDisplayName(
    customName: String,
    bluetoothName: String?,
    fallback: String = "--",
): String {
    val alias = customName.trim()
    if (alias.isNotEmpty()) return alias
    return sanitizeBleDisplayName(bluetoothName).ifBlank { fallback }
}

/**
 * Title for a saved-battery card on «Мои батареи».
 *
 * @param customName user-assigned alias; blank means unused
 * @param bluetoothName raw advertised BLE name (may contain NUL / U+FFFD)
 * @param address BLE MAC fallback when no usable name exists
 * @return label for Text in the card
 */
fun savedBatteryUiTitle(
    customName: String,
    bluetoothName: String,
    address: String,
): String {
    return deviceUiDisplayName(
        customName = customName,
        bluetoothName = bluetoothName,
        fallback = address,
    )
}
