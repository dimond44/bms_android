package ru.liferych.bms.ui.model

import androidx.compose.ui.graphics.Color
import ru.liferych.bms.TemplateCheckItem
import ru.liferych.bms.data.diagnostics.DiagnosticsSnapshot
import ru.liferych.bms.ui.theme.LiferychColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard overall-status banner — legacy updateDashboardUi semantics.
 */
data class DashboardOverallStatusUi(
    val title: String,
    val subtitle: String = "",
    val titleColor: Color,
)

enum class DiagnosticsConfigStatus {
    Ok,
    Mismatch,
    Incomplete,
    Unavailable,
    Checking,
}

enum class DiagnosticsPrimaryAction {
    Ok,
    Fix,
    None,
}

enum class DiagnosticsCategoryStatus {
    Ok,
    Mismatch,
    Incomplete,
    Skipped,
}

/**
 * One legacy diagnostics category. Successful children stay collapsed.
 */
data class DiagnosticsCategoryUi(
    val title: String,
    val status: DiagnosticsCategoryStatus,
    val problems: List<DiagnosticsProblemUi> = emptyList(),
)

/**
 * A mismatched or unreadable parameter shown inside its legacy category.
 */
data class DiagnosticsProblemUi(
    val name: String,
    val currentValue: String?,
    val statusText: String,
    val statusColor: Color,
)

/**
 * Diagnostics screen state (legacy appendDiagnosticsConfigPresentation + action buttons).
 */
sealed class DiagnosticsUiState {
    data object Loading : DiagnosticsUiState()
    data object Offline : DiagnosticsUiState()
    data class Content(
        val checkedAtText: String?,
        val overallTitle: String?,
        val overallStatus: DiagnosticsCategoryStatus?,
        val overallMarkColor: Color?,
        val categories: List<DiagnosticsCategoryUi>,
        val configStatus: DiagnosticsConfigStatus = DiagnosticsConfigStatus.Checking,
        val primaryAction: DiagnosticsPrimaryAction = DiagnosticsPrimaryAction.None,
        val isFixing: Boolean = false,
        val fixProgressText: String = "",
        val fixError: String = "",
    ) : DiagnosticsUiState()
}

/**
 * Builds Dashboard banner text from active BMS errors + template check (legacy order).
 * Config mismatch uses Compose CTA text; BMS faults stay higher priority.
 */
fun buildDashboardOverallStatus(
    activeErrors: List<String>,
    snapshot: DiagnosticsSnapshot?,
): DashboardOverallStatusUi {
    if (activeErrors.isNotEmpty()) {
        return DashboardOverallStatusUi(
            title = "Ошибка: ${activeErrors.first()}",
            subtitle = if (activeErrors.size > 1) {
                "Ещё ошибок: ${activeErrors.size - 1}"
            } else {
                ""
            },
            titleColor = Color(0xFFEF5350),
        )
    }
    val check = snapshot?.result
    val fetchStatus = snapshot?.fetchStatus
    val fetchError = snapshot?.fetchError
    return when {
        check?.status == "unavailable" ||
            (fetchStatus == "error" && check?.templateId.isNullOrBlank()) -> {
            DashboardOverallStatusUi(
                title = "⚠  Проверка конфигурации недоступна",
                subtitle = fetchError
                    ?: "Не удалось получить актуальный шаблон с сервера",
                titleColor = Color(0xFFE09600),
            )
        }
        check?.status == "checking" || fetchStatus == "fetching" -> {
            DashboardOverallStatusUi(
                title = "…  Идёт инициализация BMS",
                titleColor = Color(0xFF6F7781),
            )
        }
        check?.status == "mismatch" -> {
            DashboardOverallStatusUi(
                title = "Перейдите в диагностику для применения настроек",
                titleColor = Color(0xFFE09600),
            )
        }
        check?.status == "incomplete" -> {
            DashboardOverallStatusUi(
                title = "⚠  Проверка конфигурации неполная",
                subtitle = "Не все параметры удалось прочитать или сравнить",
                titleColor = Color(0xFFE09600),
            )
        }
        check?.status == "ok" -> {
            DashboardOverallStatusUi(
                title = "✓  Батарея в норме",
                titleColor = LiferychColors.SuccessAlt,
            )
        }
        else -> {
            DashboardOverallStatusUi(
                title = "…  Идёт инициализация BMS",
                titleColor = Color(0xFF6F7781),
            )
        }
    }
}

/**
 * Maps TemplateCheckResult → full parameter list + OK/Fix actions.
 * Shows every checked parameter (match and mismatch), not only errors.
 */
fun buildDiagnosticsContent(
    snapshot: DiagnosticsSnapshot,
    isFixing: Boolean = false,
    fixProgressText: String = "",
    fixError: String = "",
): DiagnosticsUiState.Content {
    val result = snapshot.result
    val checkedText = result.checkedAt.takeIf { it > 0 }?.let {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
    }
    val writableKeys = snapshot.writableKeys

    when {
        result.status == "unavailable" ||
            (snapshot.fetchStatus == "error" && result.templateId.isBlank()) -> {
            return DiagnosticsUiState.Content(
                checkedAtText = checkedText?.let { "Проверено: $it" },
                overallTitle = "Проверка конфигурации недоступна",
                overallStatus = DiagnosticsCategoryStatus.Incomplete,
                overallMarkColor = Color(0xFFE09600),
                categories = emptyList(),
                configStatus = DiagnosticsConfigStatus.Unavailable,
                primaryAction = DiagnosticsPrimaryAction.Ok,
                isFixing = isFixing,
                fixProgressText = fixProgressText,
                fixError = fixError,
            )
        }
        result.status == "checking" || snapshot.fetchStatus == "fetching" -> {
            return DiagnosticsUiState.Content(
                checkedAtText = checkedText?.let { "Проверено: $it" },
                overallTitle = "Идёт инициализация BMS",
                overallStatus = null,
                overallMarkColor = Color(0xFF6F7781),
                categories = emptyList(),
                configStatus = DiagnosticsConfigStatus.Checking,
                primaryAction = DiagnosticsPrimaryAction.None,
                isFixing = isFixing,
                fixProgressText = fixProgressText,
                fixError = fixError,
            )
        }
    }

    val items = (if (result.items.isNotEmpty()) {
        result.items
    } else {
        result.mismatches.map { it.copy(status = "mismatch") } +
            result.missing.map { it.copy(status = "missing") }
    }).filter { it.status != "disabled" && it.key != "series_cell_count" }

    if (items.isEmpty()) {
        return DiagnosticsUiState.Content(
            checkedAtText = checkedText?.let { "Проверено: $it" },
            overallTitle = "Нет параметров для отображения",
            overallStatus = null,
            overallMarkColor = Color(0xFF6F7781),
            categories = emptyList(),
            configStatus = DiagnosticsConfigStatus.Incomplete,
            primaryAction = DiagnosticsPrimaryAction.Ok,
            isFixing = isFixing,
            fixProgressText = fixProgressText,
            fixError = fixError,
        )
    }

    val mismatchItems = items.filter { it.status == "mismatch" }
    val hasWritableMismatch = mismatchItems.any { it.key in writableKeys }
    val categories = buildLegacyCategories(items)

    val configStatus = when (result.status) {
        "ok" -> DiagnosticsConfigStatus.Ok
        "mismatch" -> DiagnosticsConfigStatus.Mismatch
        "incomplete" -> DiagnosticsConfigStatus.Incomplete
        else -> DiagnosticsConfigStatus.Incomplete
    }

    val (overallTitle, overallStatus, overallColor) = when {
        mismatchItems.isNotEmpty() || result.status == "mismatch" -> Triple(
            "Обнаружены несоответствия конфигурации",
            DiagnosticsCategoryStatus.Mismatch,
            Color(0xFFE09600),
        )
        result.status == "ok" -> Triple(
            "Все параметры соответствуют конфигурации",
            DiagnosticsCategoryStatus.Ok,
            LiferychColors.SuccessAlt,
        )
        result.status == "incomplete" -> Triple(
            "Проверка конфигурации неполная",
            DiagnosticsCategoryStatus.Incomplete,
            Color(0xFFE09600),
        )
        else -> Triple(
            "Проверка конфигурации",
            null,
            Color(0xFF6F7781),
        )
    }

    val primaryAction = when {
        hasWritableMismatch -> DiagnosticsPrimaryAction.Fix
        else -> DiagnosticsPrimaryAction.Ok
    }

    return DiagnosticsUiState.Content(
        checkedAtText = checkedText?.let { "Проверено: $it" },
        overallTitle = overallTitle,
        overallStatus = overallStatus,
        overallMarkColor = overallColor,
        categories = categories,
        configStatus = configStatus,
        primaryAction = primaryAction,
        isFixing = isFixing,
        fixProgressText = fixProgressText,
        fixError = fixError,
    )
}

private data class LegacyCategory(
    val title: String,
    val keys: List<String>,
)

private val LEGACY_CATEGORIES = listOf(
    LegacyCategory(
        title = "Калибровка ёмкости",
        keys = listOf("soc_calibration_0", "soc_calibration_100"),
    ),
    LegacyCategory(
        title = "Параметры ячеек",
        keys = listOf(
            "cell_over_voltage",
            "cell_under_voltage",
            "pack_over_voltage",
            "pack_under_voltage",
        ),
    ),
    LegacyCategory(
        title = "Настройки температуры",
        keys = listOf(
            "charge_high_temp",
            "charge_low_temp",
            "discharge_high_temp",
            "discharge_low_temp",
        ),
    ),
    LegacyCategory(
        title = "Настройки балансировки",
        keys = listOf("balance_start_voltage", "balance_stop_voltage"),
    ),
    LegacyCategory(
        title = "Настройки спящего режима",
        keys = listOf("sleep_timeout"),
    ),
)

/**
 * Groups template items exactly like legacy diagnosticsParamGroups.
 */
private fun buildLegacyCategories(items: List<TemplateCheckItem>): List<DiagnosticsCategoryUi> {
    val byKey = items.associateBy { it.key }
    val groupedKeys = LEGACY_CATEGORIES.flatMap { it.keys }.toSet()
    val grouped = LEGACY_CATEGORIES.mapNotNull { category ->
        val children = category.keys.mapNotNull(byKey::get)
        buildCategory(category.title, children)
    }
    val standalone = items
        .filter { it.key !in groupedKeys }
        .mapNotNull { item -> buildCategory(displayLabel(item), listOf(item)) }
    return grouped + standalone
}

/**
 * Produces one compact category and expands only mismatched/missing children.
 */
private fun buildCategory(
    title: String,
    children: List<TemplateCheckItem>,
): DiagnosticsCategoryUi? {
    if (children.isEmpty()) return null
    val active = children.filter { it.status != "skipped" && it.status != "disabled" }
    if (active.isEmpty()) return null
    val status = when {
        active.any { it.status == "mismatch" } -> DiagnosticsCategoryStatus.Mismatch
        active.any { it.status == "missing" } -> DiagnosticsCategoryStatus.Incomplete
        active.all { it.status == "ok" } -> DiagnosticsCategoryStatus.Ok
        else -> DiagnosticsCategoryStatus.Incomplete
    }
    val problems = active
        .filter { it.status == "mismatch" || it.status == "missing" }
        .map(::problemUi)
    return DiagnosticsCategoryUi(title = title, status = status, problems = problems)
}

/**
 * Maps a failed check to legacy detail: label, actual value, and status.
 */
private fun problemUi(item: TemplateCheckItem): DiagnosticsProblemUi {
    val missing = item.status == "missing"
    return DiagnosticsProblemUi(
        name = item.label,
        currentValue = if (missing) null else formatValue(item.actual, item.unit),
        statusText = if (missing) "Проверено не полностью" else "Не соответствует",
        statusColor = if (missing) Color(0xFFE09600) else Color(0xFFD32F2F),
    )
}

/**
 * Applies the same standalone label override as legacy.
 */
private fun displayLabel(item: TemplateCheckItem): String {
    return when (item.key) {
        "sleep_timeout" -> "Настройки спящего режима"
        else -> item.label
    }
}

private fun formatValue(value: Double?, unit: String): String {
    val num = if (value == null) {
        "—"
    } else {
        "%.3f".format(value).trimEnd('0').trimEnd('.').replace(",", ".")
    }
    val u = unit.trim()
    return if (u.isBlank()) num else "$num $u"
}
