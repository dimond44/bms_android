package ru.liferych.bms.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.model.DiagnosticsCategoryStatus
import ru.liferych.bms.ui.model.DiagnosticsCategoryUi
import ru.liferych.bms.ui.model.DiagnosticsProblemUi
import ru.liferych.bms.ui.model.DiagnosticsPrimaryAction
import ru.liferych.bms.ui.model.DiagnosticsUiState
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens

/**
 * Shared config-diagnostics screen (Dashboard and Support entry points).
 *
 * @param state loading / offline / content
 * @param onBack return to entry source
 * @param onOk OK action → entry source when config matches / dismiss
 * @param onFix ИСПРАВИТЬ writable mismatches
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOk: () -> Unit = onBack,
    onFix: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Диагностика", onBack = onBack)
        when (state) {
            DiagnosticsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = LiferychColors.BrandYellowDark,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Проверка конфигурации BMS…",
                            color = LiferychColors.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            DiagnosticsUiState.Offline -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LiferychDimens.ScreenPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Нет связи с BMS",
                            color = LiferychColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Подключите батарею, чтобы увидеть диагностику.",
                            color = LiferychColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        DiagnosticsOkButton(onClick = onOk)
                    }
                }
            }
            is DiagnosticsUiState.Content -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = LiferychDimens.ScreenPadding)
                            .padding(top = 12.dp, bottom = 12.dp),
                    ) {
                        if (!state.checkedAtText.isNullOrBlank()) {
                            Text(
                                text = state.checkedAtText,
                                color = Color(0xFF6F7781),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        if (!state.overallTitle.isNullOrBlank()) {
                            DiagnosticsOverallStatus(
                                title = state.overallTitle,
                                status = state.overallStatus,
                                markColor = state.overallMarkColor ?: LiferychColors.TextPrimary,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        if (state.categories.isNotEmpty()) {
                            DiagnosticsCategoryList(categories = state.categories)
                        }
                        if (state.fixError.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.fixError,
                                color = Color(0xFFD32F2F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (state.isFixing && state.fixProgressText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.fixProgressText,
                                color = LiferychColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    DiagnosticsActionBar(
                        action = state.primaryAction,
                        isFixing = state.isFixing,
                        onOk = onOk,
                        onFix = onFix,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiferychDimens.ScreenPadding)
                            .padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Reusable legacy category list shared by Dashboard and Support entry points.
 */
@Composable
fun DiagnosticsCategoryList(
    categories: List<DiagnosticsCategoryUi>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        categories.forEach { category ->
            DiagnosticsCategoryCard(category)
            Spacer(Modifier.height(7.dp))
        }
    }
}

@Composable
private fun DiagnosticsOverallStatus(
    title: String,
    status: DiagnosticsCategoryStatus?,
    markColor: Color,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, LiferychColors.Border, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = LiferychColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        status?.let {
            DiagnosticsStatusIcon(status = it, tint = markColor)
        }
    }
}

@Composable
private fun DiagnosticsCategoryCard(category: DiagnosticsCategoryUi) {
    val statusColor = category.status.color()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, LiferychColors.Border, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.title,
                color = LiferychColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            DiagnosticsStatusIcon(
                status = category.status,
                tint = statusColor,
            )
        }
        category.status.subtitle()?.let { subtitle ->
            Text(
                text = subtitle,
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        category.problems.forEach { problem ->
            DiagnosticsProblemBlock(problem)
        }
    }
}

@Composable
private fun DiagnosticsProblemBlock(problem: DiagnosticsProblemUi) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = problem.name,
            color = Color(0xFF5A6068),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        problem.currentValue?.let { currentValue ->
            Text(
                text = currentValue,
                color = LiferychColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = problem.statusText,
            color = problem.statusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DiagnosticsStatusIcon(
    status: DiagnosticsCategoryStatus,
    tint: Color,
) {
    val iconRes = when (status) {
        DiagnosticsCategoryStatus.Ok -> R.drawable.ic_liferych_ok
        DiagnosticsCategoryStatus.Mismatch -> R.drawable.ic_liferych_error
        DiagnosticsCategoryStatus.Incomplete -> R.drawable.ic_liferych_warning
        DiagnosticsCategoryStatus.Skipped -> R.drawable.ic_liferych_warning
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * Resolves category status color using the legacy palette.
 */
private fun DiagnosticsCategoryStatus.color(): Color {
    return when (this) {
        DiagnosticsCategoryStatus.Ok -> LiferychColors.SuccessAlt
        DiagnosticsCategoryStatus.Mismatch -> Color(0xFFD32F2F)
        DiagnosticsCategoryStatus.Incomplete -> Color(0xFFE09600)
        DiagnosticsCategoryStatus.Skipped -> Color(0xFF6F7781)
    }
}

/**
 * Resolves the category-level legacy status text.
 */
private fun DiagnosticsCategoryStatus.subtitle(): String? {
    return when (this) {
        DiagnosticsCategoryStatus.Ok -> null
        DiagnosticsCategoryStatus.Mismatch -> "Не соответствует"
        DiagnosticsCategoryStatus.Incomplete -> "Проверено не полностью"
        DiagnosticsCategoryStatus.Skipped -> "Не поддерживается"
    }
}

@Composable
private fun DiagnosticsActionBar(
    action: DiagnosticsPrimaryAction,
    isFixing: Boolean,
    onOk: () -> Unit,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isFixing || action == DiagnosticsPrimaryAction.Fix -> {
            val enabled = !isFixing && action == DiagnosticsPrimaryAction.Fix
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = modifier
                    .height(52.dp)
                    .clip(shape)
                    .background(
                        if (enabled) Color(0xFFE53935) else Color(0xFFA0A0A0),
                    )
                    .clickable(enabled = enabled, onClick = onFix),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isFixing) "ИСПРАВЛЕНИЕ…" else "ИСПРАВИТЬ",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        action == DiagnosticsPrimaryAction.Ok -> {
            DiagnosticsOkButton(onClick = onOk, modifier = modifier)
        }
        else -> Unit
    }
}

@Composable
private fun DiagnosticsOkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(LiferychColors.BrandYellow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ОК",
            color = LiferychColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
