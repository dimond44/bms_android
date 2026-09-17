package ru.liferych.bms.data.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.liferych.bms.BmsApiConfig
import ru.liferych.bms.BmsConfigTemplate
import ru.liferych.bms.BmsTemplateParameter
import ru.liferych.bms.TemplateCheckItem
import ru.liferych.bms.TemplateCheckResult
import ru.liferych.bms.domain.model.BatteryState
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Legacy-compatible config diagnostics for Compose:
 * server template + cached Modbus config registers (no new BLE commands).
 *
 * Prefs name and keys match MainActivity (`bms_config_cache`).
 */
class ConfigDiagnosticsRepository(
    appContext: Context,
) {
    private val app = appContext.applicationContext
    private val prefs = app.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Runs template check for [bmsUid] using BatteryState for series/hardware hints.
     *
     * @param bmsUid DL-… identity (same as legacy bmsUid)
     * @param battery live BatteryState (series / HW version only)
     * @param bluetoothName advertised BLE name for cache suffix fallback
     * @return check result + fetch meta for Dashboard / Diagnostics UI
     */
    fun refresh(
        bmsUid: String,
        battery: BatteryState,
        bluetoothName: String,
    ): DiagnosticsSnapshot {
        val fetch = fetchServerTemplate()
        val registers = loadCachedRegisters(bmsUid)
        val hardwareFamily = hardwareFamily(battery.bmsHwVersion)
        val series = resolvedSeriesCount(battery)

        if (fetch.status == "error" && fetch.template == null) {
            return DiagnosticsSnapshot(
                result = TemplateCheckResult(
                    templateId = "",
                    templateVersion = 0,
                    status = "unavailable",
                    checkedAt = System.currentTimeMillis(),
                    seriesCount = series,
                    missing = listOf(
                        TemplateCheckItem(
                            key = "server_template",
                            label = "Шаблон конфигурации",
                            expected = null,
                            actual = null,
                            unit = "",
                            tolerance = 0.0,
                            reason = fetch.error ?: "server_template_unavailable",
                            status = "missing",
                        ),
                    ),
                    hardwareFamily = hardwareFamily,
                ),
                fetchStatus = fetch.status,
                fetchError = fetch.error,
                template = null,
                registers = registers,
                writableKeys = emptySet(),
            )
        }

        val template = fetch.template
        if (template == null || registers.isEmpty()) {
            return DiagnosticsSnapshot(
                result = TemplateCheckResult(
                    templateId = template?.id.orEmpty(),
                    templateVersion = template?.version ?: 0,
                    status = "checking",
                    checkedAt = 0L,
                    seriesCount = series,
                    hardwareFamily = hardwareFamily,
                    templateUpdatedAt = template?.updatedAt ?: 0L,
                ),
                fetchStatus = fetch.status,
                fetchError = fetch.error,
                template = template,
                registers = registers,
                writableKeys = template.writableKeys(),
            )
        }

        return DiagnosticsSnapshot(
            result = evaluateTemplateCheck(
                template = template,
                registers = registers,
                series = series,
                hardwareFamily = hardwareFamily,
            ),
            fetchStatus = fetch.status,
            fetchError = fetch.error,
            template = template,
            registers = registers,
            writableKeys = template.writableKeys(),
        )
    }

    /**
     * Persists updated register map for [bmsUid] into legacy prefs cache.
     */
    fun saveRegisters(bmsUid: String, registers: Map<Int, Int>) {
        if (registers.isEmpty()) return
        val suffix = bmsUid.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val obj = JSONObject()
        for ((addr, value) in registers) {
            obj.put("0x%04X".format(addr), value)
        }
        prefs.edit()
            .putString("config_registers_$suffix", obj.toString())
            .putLong("config_saved_at_$suffix", System.currentTimeMillis())
            .apply()
    }

    /**
     * Loads cached registers for [bmsUid].
     */
    fun loadRegisters(bmsUid: String): Map<Int, Int> = loadCachedRegisters(bmsUid)

    private fun fetchServerTemplate(): TemplateFetch {
        // Prefer live GET; fall back to prefs cache (legacy).
        return try {
            val json = httpGetJson("/api/v1/config-template")
            if (json?.optBoolean("ok") == true && json.has("template")) {
                val templateObj = json.getJSONObject("template")
                prefs.edit()
                    .putString("server_template_cache", templateObj.toString())
                    .putLong("server_template_cached_at", System.currentTimeMillis())
                    .apply()
                TemplateFetch("ok", parseTemplate(templateObj), null)
            } else {
                val raw = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: if (json == null) "server_unreachable" else "template_response_invalid"
                val cached = prefs.getString("server_template_cache", null)
                if (!cached.isNullOrBlank()) {
                    TemplateFetch("ok", parseTemplate(JSONObject(cached)), null)
                } else {
                    TemplateFetch("error", null, humanizeFetchError(raw))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "template fetch failed: ${e.message}")
            val cached = prefs.getString("server_template_cache", null)
            if (!cached.isNullOrBlank()) {
                try {
                    TemplateFetch("ok", parseTemplate(JSONObject(cached)), null)
                } catch (_: Exception) {
                    TemplateFetch("error", null, humanizeFetchError(e.message ?: "server_unreachable"))
                }
            } else {
                TemplateFetch("error", null, humanizeFetchError(e.message ?: "server_unreachable"))
            }
        }
    }

    private fun loadCachedRegisters(bmsUid: String): Map<Int, Int> {
        val suffix = bmsUid.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val saved = prefs.getString("config_registers_$suffix", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(saved)
            val out = LinkedHashMap<Int, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val addr = key.removePrefix("0x").toIntOrNull(16) ?: continue
                out[addr] = obj.optInt(key)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun evaluateTemplateCheck(
        template: BmsConfigTemplate,
        registers: Map<Int, Int>,
        series: Int?,
        hardwareFamily: String,
    ): TemplateCheckResult {
        val checkedAt = System.currentTimeMillis()
        return try {
            val mismatches = mutableListOf<TemplateCheckItem>()
            val missing = mutableListOf<TemplateCheckItem>()
            val unverified = mutableListOf<TemplateCheckItem>()
            val items = mutableListOf<TemplateCheckItem>()

            for (parameter in template.parameters) {
                val expected = parameter.expected ?: series?.let { parameter.expectedBySeries[it] }
                val hasActual = if (parameter.key == "series_cell_count") {
                    series != null
                } else {
                    registers.containsKey(parameter.register)
                }
                val actual: Double? = when {
                    !hasActual -> null
                    parameter.key == "series_cell_count" -> series?.toDouble()
                    else -> {
                        val raw = registers[parameter.register] ?: null
                        if (raw == null) null else (raw / parameter.scale) + parameter.offset
                    }
                }

                if (!parameter.enabled || parameter.enforcement == "informational") continue

                if (shouldSkip(parameter, hardwareFamily)) {
                    items += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        expected,
                        if (hasActual) actual else null,
                        parameter.unit,
                        parameter.tolerance,
                        "skipped_for_hardware",
                        status = "skipped",
                    )
                    continue
                }

                val item = when {
                    parameter.expectedBySeries.isNotEmpty() && series == null -> {
                        TemplateCheckItem(
                            parameter.key, parameter.label, null, null,
                            parameter.unit, parameter.tolerance, "unsupported_series",
                            status = "missing",
                        ).also { missing += it }
                    }
                    !hasActual -> {
                        TemplateCheckItem(
                            parameter.key, parameter.label, expected, null,
                            parameter.unit, parameter.tolerance, "register_missing",
                            status = "missing",
                        ).also { missing += it }
                    }
                    expected == null -> {
                        TemplateCheckItem(
                            parameter.key, parameter.label, null, null,
                            parameter.unit, parameter.tolerance, "expected_value_missing",
                            status = "missing",
                        ).also { missing += it }
                    }
                    abs((actual ?: 0.0) - expected) > parameter.tolerance -> {
                        TemplateCheckItem(
                            parameter.key, parameter.label, expected, actual,
                            parameter.unit, parameter.tolerance,
                            status = "mismatch",
                        ).also { mismatches += it }
                    }
                    else -> {
                        TemplateCheckItem(
                            parameter.key, parameter.label, expected, actual,
                            parameter.unit, parameter.tolerance,
                            status = "ok",
                        )
                    }
                }
                items += item
            }

            val status = when {
                mismatches.isNotEmpty() -> "mismatch"
                missing.isNotEmpty() || series == null || series !in template.supportedSeries ->
                    "incomplete"
                else -> "ok"
            }
            TemplateCheckResult(
                template.id,
                template.version,
                status,
                checkedAt,
                series,
                mismatches,
                missing,
                unverified,
                hardwareFamily,
                items,
                template.updatedAt,
            )
        } catch (e: Exception) {
            TemplateCheckResult(
                templateId = template.id,
                templateVersion = template.version,
                status = "incomplete",
                checkedAt = checkedAt,
                seriesCount = series,
                missing = listOf(
                    TemplateCheckItem(
                        key = "template_load_error",
                        label = "Шаблон конфигурации",
                        expected = null,
                        actual = null,
                        unit = "",
                        tolerance = 0.0,
                        reason = "template_load_error: ${(e.message ?: e.javaClass.simpleName).take(160)}",
                        status = "missing",
                    ),
                ),
                hardwareFamily = hardwareFamily,
                templateUpdatedAt = template.updatedAt,
            )
        }
    }

    private fun shouldSkip(parameter: BmsTemplateParameter, hardwareFamily: String): Boolean {
        if (hardwareFamily != HARDWARE_FAMILY_STANDARD && parameter.key in R10K_SKIPPED) return true
        return hardwareFamily in parameter.skipFor
    }

    private fun parseTemplate(root: JSONObject): BmsConfigTemplate {
        val templateId = root.optString("id").trim()
        val templateVersion = root.optInt("version", 0)
        if (templateId.isBlank() || templateVersion <= 0) {
            throw IllegalArgumentException("invalid_template_header")
        }
        val parametersJson = root.optJSONArray("parameters")
            ?: throw IllegalArgumentException("template_parameters_missing")
        if (parametersJson.length() <= 0) {
            throw IllegalArgumentException("template_parameters_empty")
        }
        val parameters = mutableListOf<BmsTemplateParameter>()
        for (i in 0 until parametersJson.length()) {
            val item = parametersJson.optJSONObject(i) ?: continue
            val key = item.optString("key").trim()
            if (key.isBlank()) continue
            val registerText = item.optString("register").trim()
            if (registerText.isBlank()) continue
            val register = try {
                registerText.removePrefix("0x").removePrefix("0X").toInt(16)
            } catch (_: Exception) {
                continue
            }
            val expectedBySeries = mutableMapOf<Int, Double>()
            item.optJSONObject("expected_by_series")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val seriesKey = keys.next()
                    val series = seriesKey.toIntOrNull() ?: continue
                    if (!values.isNull(seriesKey)) {
                        expectedBySeries[series] = values.getDouble(seriesKey)
                    }
                }
            }
            val enforcement = item.optString("enforcement", "required")
            if (enforcement != "required" && enforcement != "informational") continue
            if (!item.has("scale") || item.isNull("scale")) continue
            val scale = item.getDouble("scale")
            if (scale == 0.0) continue
            val tolerance = item.optDouble("tolerance", 0.0)
            if (tolerance < 0.0) continue
            val expected = if (item.has("expected") && !item.isNull("expected")) {
                item.getDouble("expected")
            } else {
                null
            }
            val enabled = when {
                item.has("enabled") && !item.isNull("enabled") -> item.getBoolean("enabled")
                item.has("check_enabled") && !item.isNull("check_enabled") ->
                    item.getBoolean("check_enabled")
                enforcement == "informational" -> false
                else -> true
            }
            val writable = when {
                item.has("writable") && !item.isNull("writable") -> item.getBoolean("writable")
                item.has("write_enabled") && !item.isNull("write_enabled") ->
                    item.getBoolean("write_enabled")
                else -> key == "series_cell_count" || key in WRITE_ORDER
            }
            if (expected == null && expectedBySeries.isEmpty()) {
                if (!(writable && !enabled)) continue
            }
            val skipFor = buildSet {
                val skipJson = item.optJSONArray("skip_for") ?: return@buildSet
                for (j in 0 until skipJson.length()) {
                    val family = skipJson.optString(j)
                    if (family.isNotBlank()) add(family)
                }
            }
            parameters += BmsTemplateParameter(
                key = key,
                label = item.optString("label").ifBlank { key },
                register = register,
                scale = scale,
                offset = item.optDouble("offset", 0.0),
                unit = item.optString("unit", ""),
                tolerance = tolerance,
                enforcement = enforcement,
                expected = expected,
                expectedBySeries = expectedBySeries,
                reason = item.optString("reason").takeIf { it.isNotBlank() },
                skipFor = skipFor,
                enabled = enabled,
                writable = writable,
            )
        }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("template_parameters_unusable")
        }
        val supported = mutableSetOf<Int>()
        root.optJSONArray("supported_series")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, -1)
                if (v > 0) supported += v
            }
        }
        return BmsConfigTemplate(
            id = templateId,
            version = templateVersion,
            chemistry = root.optString("chemistry", ""),
            supportedSeries = supported.ifEmpty { setOf(4, 8) },
            parameters = parameters,
            updatedAt = root.optLong("updated_at", 0L),
        )
    }

    private fun httpGetJson(path: String): JSONObject? {
        return try {
            val base = BmsApiConfig.BASE_URL.trimEnd('/')
            val url = if (path.startsWith("/")) "$base$path" else "$base/$path"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", BmsApiConfig.API_KEY)
            }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            conn.disconnect()
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun humanizeFetchError(raw: String): String {
        val text = raw.trim()
        return when {
            text.equals("Failed requirement.", ignoreCase = true) ->
                "Серверный шаблон содержит параметр без эталонного значения"
            text == "invalid_template_header" -> "Сервер вернул некорректный шаблон"
            text == "template_parameters_missing" || text == "template_parameters_empty" ->
                "Сервер вернул пустой шаблон конфигурации"
            text == "template_parameters_unusable" ->
                "В шаблоне нет параметров, пригодных для проверки"
            text == "unauthorized" -> "Нет доступа к шаблону на сервере"
            text == "template_not_found" -> "Для этой BMS не найден шаблон конфигурации"
            text == "server_unreachable" -> "Не удалось получить шаблон с сервера"
            text.startsWith("HTTP ") || text.contains("timeout", ignoreCase = true) ->
                "Не удалось получить шаблон с сервера"
            else -> text.take(160)
        }
    }

    companion object {
        private const val TAG = "ConfigDiagnostics"
        private const val CONFIG_PREFS_NAME = "bms_config_cache"
        private const val HARDWARE_FAMILY_STANDARD = "standard"
        private const val HARDWARE_FAMILY_R10K = "r10k"

        private val R10K_SKIPPED = setOf(
            "soc_calibration_0",
            "soc_calibration_100",
            "balance_start_voltage",
            "balance_stop_voltage",
            "balance_delta",
        )

        private val WRITE_ORDER = listOf(
            "sleep_timeout",
            "cell_over_voltage",
            "cell_under_voltage",
            "pack_over_voltage",
            "pack_under_voltage",
            "charge_high_temp",
            "charge_low_temp",
            "discharge_high_temp",
            "discharge_low_temp",
            "balance_start_voltage",
            "balance_stop_voltage",
            "balance_delta",
            "soc_calibration_0",
            "soc_calibration_100",
        )

        /**
         * Builds Daly-style BMS uid from MAC / advertised name (legacy bmsUid).
         */
        fun bmsUid(address: String?, bluetoothName: String?): String {
            val advertised = bluetoothName.orEmpty().trim()
            if (advertised.matches(Regex("^DL-[0-9A-Fa-f]+$"))) return advertised
            val mac = address.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
            if (mac.isNotBlank()) return "DL-$mac"
            return advertised.ifBlank { address ?: "unknown_bms" }
        }

        fun resolvedSeriesCount(battery: BatteryState): Int? {
            battery.cellCount?.takeIf { it == 4 || it == 8 }?.let { return it }
            val cells = battery.cells.size
            if (cells == 4 || cells == 8) return cells
            val voltage = battery.voltage ?: return null
            return when {
                voltage < 18.0 -> 4
                voltage < 36.0 -> 8
                else -> null
            }
        }

        fun hardwareFamily(hwVersion: String?): String {
            val hw = hwVersion.orEmpty()
            return if (hw.contains("R24", ignoreCase = true)) {
                HARDWARE_FAMILY_STANDARD
            } else {
                HARDWARE_FAMILY_R10K
            }
        }
    }
}

/**
 * Snapshot for Compose diagnostics UI / Dashboard banner.
 */
data class DiagnosticsSnapshot(
    val result: TemplateCheckResult,
    val fetchStatus: String,
    val fetchError: String?,
    val template: BmsConfigTemplate? = null,
    val registers: Map<Int, Int> = emptyMap(),
    val writableKeys: Set<String> = emptySet(),
)

private fun BmsConfigTemplate?.writableKeys(): Set<String> {
    if (this == null) return emptySet()
    return parameters.filter { it.writable && it.enabled }.map { it.key }.toSet()
}

private data class TemplateFetch(
    val status: String,
    val template: BmsConfigTemplate?,
    val error: String?,
)
