package ru.liferych.bms.ui.screens.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.liferych.bms.telemetry.TelemetryHistoryChartState
import ru.liferych.bms.telemetry.TelemetryHistoryPeriod
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography
import ru.liferych.bms.ui.viewmodel.ChartsViewModel

/**
 * Local Voltage/Current history charts for the selected BMS.
 */
@Composable
fun ChartsScreen(
    bmsUid: String,
    chartsViewModel: ChartsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val period by chartsViewModel.period.collectAsStateWithLifecycle()
    val chartState by chartsViewModel.chartState.collectAsStateWithLifecycle()

    LaunchedEffect(bmsUid) {
        chartsViewModel.bindBattery(bmsUid)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LiferychColors.Background,
        topBar = { LiferychTopBar(title = "Графики", onBack = onBack) },
    ) { padding ->
        ChartsContent(
            period = period,
            chartState = chartState,
            onSelectPeriod = chartsViewModel::selectPeriod,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun ChartsContent(
    period: TelemetryHistoryPeriod,
    chartState: TelemetryHistoryChartState,
    onSelectPeriod: (TelemetryHistoryPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(LiferychDimens.ScreenPadding),
    ) {
        PeriodChipRow(
            selected = period,
            onSelect = onSelectPeriod,
        )
        Spacer(Modifier.height(16.dp))

        when {
            chartState.loading && chartState.points.isEmpty() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = LiferychColors.BrandYellowDark,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(22.dp),
                    )
                    Spacer(Modifier.padding(6.dp))
                    Text(
                        text = "Загрузка истории…",
                        style = LiferychTypography.bodyMedium,
                        color = LiferychColors.TextSecondary,
                    )
                }
            }
            chartState.empty -> {
                Text(
                    text = "Нет данных за выбранный период",
                    style = LiferychTypography.bodyMedium,
                    color = LiferychColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                )
            }
            else -> {
                HistoryLineChart(
                    title = "Напряжение",
                    unit = "В",
                    kind = HistorySeriesKind.Voltage,
                    points = chartState.points,
                    fromMs = chartState.fromMs,
                    toMs = chartState.toMs,
                    period = chartState.period,
                    seriesMin = chartState.voltageMin,
                    seriesMax = chartState.voltageMax,
                    seriesAvg = chartState.voltageAvg,
                    lineColor = LiferychColors.BrandYellowDark,
                )
                Spacer(Modifier.height(20.dp))
                HistoryLineChart(
                    title = "Ток",
                    unit = "А",
                    kind = HistorySeriesKind.Current,
                    points = chartState.points,
                    fromMs = chartState.fromMs,
                    toMs = chartState.toMs,
                    period = chartState.period,
                    seriesMin = chartState.currentMin,
                    seriesMax = chartState.currentMax,
                    seriesAvg = chartState.currentAvg,
                    lineColor = LiferychColors.ChartLink,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Точек на графике: ${chartState.points.size}" +
                        if (chartState.rawCount > chartState.points.size) {
                            " (из ${chartState.rawCount} измерений)"
                        } else {
                            ""
                        },
                    style = LiferychTypography.labelMedium,
                    color = LiferychColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PeriodChipRow(
    selected: TelemetryHistoryPeriod,
    onSelect: (TelemetryHistoryPeriod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TelemetryHistoryPeriod.entries.forEach { period ->
            val active = period == selected
            val shape = RoundedCornerShape(10.dp)
            Text(
                text = period.label,
                style = LiferychTypography.labelLarge,
                color = if (active) LiferychColors.TextOnAccent else LiferychColors.TextSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) LiferychColors.BrandYellow else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (active) LiferychColors.BrandYellowDark else LiferychColors.Border,
                        shape = shape,
                    )
                    .clickable { onSelect(period) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Preview(showBackground = true, name = "ChartsScreenPreview")
@Composable
private fun ChartsScreenPreview() {
    LiferychTheme {
        ChartsContent(
            period = TelemetryHistoryPeriod.H24,
            chartState = TelemetryHistoryChartState(
                period = TelemetryHistoryPeriod.H24,
                fromMs = 0L,
                toMs = 1L,
                points = emptyList(),
                voltageMin = null,
                voltageMax = null,
                voltageAvg = null,
                currentMin = null,
                currentAvg = null,
                currentMax = null,
                rawCount = 0,
                empty = true,
            ),
            onSelectPeriod = {},
        )
    }
}
