package ru.liferych.bms.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.CellState
import ru.liferych.bms.ui.components.CapacityInnerTile
import ru.liferych.bms.ui.components.ChartLinkRow
import ru.liferych.bms.ui.components.ClassicMetricTile
import ru.liferych.bms.ui.components.InfoOneLineTile
import ru.liferych.bms.ui.components.LiferychBrandHeader
import ru.liferych.bms.ui.components.LiferychCard
import ru.liferych.bms.ui.components.MetricPairRow
import ru.liferych.bms.ui.components.MosToggleTile
import ru.liferych.bms.ui.components.ScreenStateHost
import ru.liferych.bms.ui.components.SocGauge
import ru.liferych.bms.ui.components.socStatusLabel
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography
import kotlin.math.abs

@Composable
fun DashboardScreen(
    batteryState: BatteryState,
    connectionState: BmsConnectionState,
    screenStatus: ScreenUiStatus,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    batteryName: String = "Батарея",
    serialNumber: String = "--",
    bmsVersion: String = "--",
    onOpenCharts: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychBrandHeader(onBack = onBack)
        ScreenStateHost(
            status = screenStatus,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            disconnectedMessage = "BMS не подключена",
            disconnectedSubtitle = "Подключите батарею, чтобы увидеть данные",
            errorMessage = "Ошибка связи с BMS",
            errorSubtitle = "Проверьте Bluetooth и попробуйте снова",
            loadingMessage = "Подключение к BMS…",
            connectedContent = {
                DashboardLegacyContent(
                    batteryName = batteryName,
                    serialNumber = serialNumber,
                    bmsVersion = bmsVersion,
                    batteryState = batteryState,
                    onOpenCharts = onOpenCharts,
                )
            },
        )
    }
}

@Composable
private fun DashboardLegacyContent(
    batteryName: String,
    serialNumber: String,
    bmsVersion: String,
    batteryState: BatteryState,
    onOpenCharts: () -> Unit,
) {
    val (socLabel, socColor) = socStatusLabel(batteryState.soc)
    val statusText = when {
        batteryState.errors.isNotEmpty() -> "Ошибка"
        else -> "Норма"
    }
    val statusColor = when {
        batteryState.errors.isNotEmpty() -> LiferychColors.Error
        else -> LiferychColors.SuccessAlt
    }
    val stateText = when {
        batteryState.errors.isNotEmpty() -> "Авария"
        (batteryState.current ?: 0.0) > 0.2 -> "Заряд"
        (batteryState.current ?: 0.0) < -0.2 -> "Разряд"
        else -> "Активна"
    }
    val stateColor = when {
        batteryState.errors.isNotEmpty() -> LiferychColors.Error
        else -> LiferychColors.SuccessAlt
    }
    val overallTitle = when {
        batteryState.errors.isNotEmpty() -> "⚠  Есть предупреждения"
        else -> "✓  Батарея в норме"
    }
    val overallColor = when {
        batteryState.errors.isNotEmpty() -> LiferychColors.Warning
        else -> LiferychColors.SuccessAlt
    }
    val cellDiffMv = batteryState.cellDiffV?.let { abs(it * 1000.0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = LiferychDimens.Space8, bottom = LiferychDimens.Space24),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoOneLineTile(
                label = "Имя устройства",
                value = batteryName.ifBlank { "--" },
                iconRes = R.drawable.icon_device,
                modifier = Modifier.weight(1f),
            )
            InfoOneLineTile(
                label = "Серийный номер",
                value = serialNumber.ifBlank { "--" },
                iconRes = R.drawable.ic_liferych_serial,
                modifier = Modifier.weight(1f),
            )
            InfoOneLineTile(
                label = "Версия BMS",
                value = bmsVersion.ifBlank { "--" },
                iconRes = R.drawable.ic_liferych_chip,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        // Hero: SOC + capacity
        LiferychCard(elevated = true, padding = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(0.95f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SocGauge(socPercent = batteryState.soc)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = socLabel,
                        color = socColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    ChartLinkRow(onClick = onOpenCharts)
                }
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapacityInnerTile(
                        label = "Полная ёмкость",
                        value = batteryState.fullCapacityAh?.let { "%.0f А·ч".format(it) } ?: "-- А·ч",
                        iconRes = R.drawable.ic_liferych_capacity,
                    )
                    CapacityInnerTile(
                        label = "Осталось",
                        value = batteryState.remainingCapacityAh?.let { "%.1f А·ч".format(it) }
                            ?: "-- А·ч",
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        MetricPairRow(
            left = { mod ->
                ClassicMetricTile(
                    label = "Напряжение",
                    value = batteryState.voltage?.let { "%.2f В".format(it) } ?: "-- В",
                    iconRes = R.drawable.ic_liferych_voltage,
                    modifier = mod,
                )
            },
            right = { mod ->
                ClassicMetricTile(
                    label = "Ток",
                    value = batteryState.current?.let { "%.1f А".format(it) } ?: "-- А",
                    iconRes = R.drawable.ic_liferych_current,
                    modifier = mod,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        MetricPairRow(
            left = { mod ->
                ClassicMetricTile(
                    label = "Температура",
                    value = (batteryState.maxTemp ?: batteryState.temperatures.maxOrNull())
                        ?.let { "$it °C" } ?: "-- °C",
                    iconRes = R.drawable.ic_liferych_temperature,
                    modifier = mod,
                )
            },
            right = { mod ->
                ClassicMetricTile(
                    label = "Количество ячеек",
                    value = batteryState.cellCount?.toString()
                        ?: batteryState.cells.size.takeIf { it > 0 }?.toString()
                        ?: "--",
                    iconRes = R.drawable.ic_liferych_cells,
                    modifier = mod,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        MetricPairRow(
            left = { mod ->
                MosToggleTile(
                    label = "MOS зарядки",
                    enabled = batteryState.chargeMosEnabled,
                    modifier = mod,
                )
            },
            right = { mod ->
                MosToggleTile(
                    label = "MOS разрядки",
                    enabled = batteryState.dischargeMosEnabled,
                    modifier = mod,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        MetricPairRow(
            left = { mod ->
                ClassicMetricTile(
                    label = "Статус BMS",
                    value = statusText,
                    iconRes = if (batteryState.errors.isNotEmpty()) {
                        R.drawable.ic_liferych_error
                    } else {
                        R.drawable.ic_liferych_ok
                    },
                    iconTint = statusColor,
                    valueColor = statusColor,
                    modifier = mod,
                )
            },
            right = { mod ->
                ClassicMetricTile(
                    label = "Состояние",
                    value = stateText,
                    iconRes = R.drawable.icon_state,
                    valueColor = stateColor,
                    modifier = mod,
                )
            },
        )

        Spacer(Modifier.height(8.dp))

        // Cells card
        LiferychCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Напряжение по ячейкам",
                    style = LiferychTypography.titleMedium.copy(fontSize = 14.sp),
                )
                if (cellDiffMv != null) {
                    Text(
                        text = "Разбег: %.0f мВ".format(cellDiffMv),
                        style = LiferychTypography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (batteryState.cells.isEmpty()) {
                Text(text = "Нет данных", style = LiferychTypography.bodyMedium)
            } else {
                val minV = batteryState.cells.minOf { it.voltage }
                val maxV = batteryState.cells.maxOf { it.voltage }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    batteryState.cells.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { cell ->
                                CellMiniTile(
                                    cell = cell,
                                    minV = minV,
                                    maxV = maxV,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LiferychCard {
            Text(
                text = overallTitle,
                color = overallColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CellMiniTile(
    cell: CellState,
    minV: Double,
    maxV: Double,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val range = (maxV - minV).coerceAtLeast(0.001)
    val frac = ((cell.voltage - minV) / range).toFloat().coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, LiferychColors.Border, shape)
            .padding(10.dp),
    ) {
        Text(
            text = "Ячейка ${cell.index}",
            style = LiferychTypography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "%.3f В".format(cell.voltage),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = LiferychColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LiferychColors.Divider),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.coerceAtLeast(0.08f))
                    .height(4.dp)
                    .background(
                        if (cell.balancing) LiferychColors.Warning else LiferychColors.Success,
                    ),
            )
        }
    }
}

@Preview(showBackground = true, name = "DashboardConnectedPreview", heightDp = 1100)
@Composable
private fun DashboardConnectedPreview() {
    LiferychTheme {
        DashboardScreen(
            batteryName = "DALY-BMS-16S",
            serialNumber = "224LG151200441",
            bmsVersion = "JHB-R24TK-V2.1",
            batteryState = previewBatteryState(),
            connectionState = BmsConnectionState.Connected("AA:BB:CC:DD:EE:01"),
            screenStatus = ScreenUiStatus.Connected,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "DashboardDisconnectedPreview")
@Composable
private fun DashboardDisconnectedPreview() {
    LiferychTheme {
        DashboardScreen(
            batteryState = BatteryState(),
            connectionState = BmsConnectionState.Disconnected,
            screenStatus = ScreenUiStatus.Disconnected,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "DashboardErrorPreview")
@Composable
private fun DashboardErrorPreview() {
    LiferychTheme {
        DashboardScreen(
            batteryState = BatteryState(),
            connectionState = BmsConnectionState.Error("GATT failed"),
            screenStatus = ScreenUiStatus.Error,
            onBack = {},
        )
    }
}

internal fun previewBatteryState(): BatteryState {
    val cells = (1..16).map { index ->
        CellState(
            index = index,
            voltage = 3.24 + (index % 5) * 0.005,
            balancing = index in setOf(1, 4, 9),
        )
    }
    return BatteryState(
        voltage = 51.8,
        current = -12.4,
        soc = 78.0,
        remainingCapacityAh = 83.0,
        fullCapacityAh = 106.0,
        temperatures = listOf(27, 26, 28),
        minTemp = 26,
        maxTemp = 28,
        cells = cells,
        cellDiffV = cells.maxOf { it.voltage } - cells.minOf { it.voltage },
        chargeMosEnabled = true,
        dischargeMosEnabled = true,
        cycleCount = 142,
        cellCount = 16,
        balancingCells = setOf(1, 4, 9),
        lastUpdatedAt = 1_725_000_000_000L,
    )
}
