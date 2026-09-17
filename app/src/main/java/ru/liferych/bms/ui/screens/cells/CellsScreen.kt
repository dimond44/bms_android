package ru.liferych.bms.ui.screens.cells

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.CellState
import ru.liferych.bms.ui.components.LiferychBrandHeader
import ru.liferych.bms.ui.components.LiferychCard
import ru.liferych.bms.ui.components.ScreenStateHost
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.screens.dashboard.previewBatteryState
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import kotlin.math.abs

/**
 * CLIENT Cells screen — visual port of legacy dashboard cells block
 * ([ru.liferych.bms.MainActivity.renderCells]) expanded to a dedicated screen.
 */
@Composable
fun CellsScreen(
    batteryState: BatteryState,
    screenStatus: ScreenUiStatus,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            emptyMessage = "Нет данных по ячейкам",
            disconnectedMessage = "BMS не подключена",
            disconnectedSubtitle = "Подключите батарею, чтобы увидеть ячейки",
            connectedContent = {
                CellsLegacyContent(batteryState = batteryState)
            },
        )
    }
}

@Composable
private fun CellsLegacyContent(batteryState: BatteryState) {
    val cells = batteryState.cells.sortedBy { it.index }
    if (cells.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LiferychDimens.ScreenPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Нет данных по ячейкам",
                color = LiferychColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
        return
    }

    val minCell = cells.minBy { it.voltage }
    val maxCell = cells.maxBy { it.voltage }
    val minV = minCell.voltage
    val maxV = maxCell.voltage
    val deltaV = batteryState.cellDiffV ?: (maxV - minV)
    val deltaMv = abs(deltaV * 1000.0)
    val count = (batteryState.cellCount?.takeIf { it > 0 } ?: cells.size).coerceIn(1, 24)
    val columns = when {
        count <= 4 -> count
        count <= 8 -> 2
        else -> 4
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = LiferychDimens.Space8, bottom = LiferychDimens.Space24),
    ) {
        // Compact summary derived from cells (legacy-style bordered tiles)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CellsSummaryTile(
                label = "Min",
                value = "Яч. ${minCell.index}",
                sub = "%.3f В".format(minV),
                modifier = Modifier.weight(1f),
            )
            CellsSummaryTile(
                label = "Max",
                value = "Яч. ${maxCell.index}",
                sub = "%.3f В".format(maxV),
                modifier = Modifier.weight(1f),
            )
            CellsSummaryTile(
                label = "Разбег",
                value = "%.0f мВ".format(deltaMv),
                sub = "%.3f В".format(deltaV),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        LiferychCard {
            Text(
                text = "Напряжение по ячейкам",
                color = LiferychColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )

            if (batteryState.balancingCells.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_liferych_balance),
                        contentDescription = null,
                        tint = LiferychColors.Warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Балансировка: " +
                            batteryState.balancingCells.sorted().joinToString(", ") { "№$it" },
                        color = LiferychColors.Warning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val displayCells = (1..count).map { index ->
                cells.firstOrNull { it.index == index }
                    ?: CellState(index = index, voltage = 0.0, balancing = false)
            }
            displayCells.chunked(columns).forEach { rowCells ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowCells.forEach { cell ->
                        LegacyCellTile(
                            cell = cell,
                            compact = count > 8,
                            known = cells.any { it.index == cell.index },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowCells.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CellsSummaryTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = LiferychColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = LiferychColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = sub,
            color = LiferychColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Port of legacy cell box: label, voltage, horizontal bar (mV / 3650).
 * Balancing → warning-colored bar (legacy has no per-cell badge; color is the cue).
 */
@Composable
private fun LegacyCellTile(
    cell: CellState,
    compact: Boolean,
    known: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val balancing = cell.balancing
    val barColor = when {
        !known -> LiferychColors.Divider
        balancing -> LiferychColors.Warning
        else -> LiferychColors.SuccessAlt
    }
    val progress = if (known) {
        ((cell.voltage * 1000.0) / 3650.0).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .heightIn(min = if (compact) 76.dp else 88.dp)
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
    ) {
        Text(
            text = "Ячейка ${cell.index}",
            color = LiferychColors.TextSecondary.copy(alpha = 0.95f),
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (known) "%.3f В".format(cell.voltage) else "--",
            color = LiferychColors.TextPrimary,
            fontSize = if (compact) 13.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LiferychColors.Border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceAtLeast(0.04f))
                    .height(7.dp)
                    .background(barColor),
            )
        }
        if (balancing) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_liferych_balance),
                    contentDescription = null,
                    tint = LiferychColors.Warning,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Баланс",
                    color = LiferychColors.Warning,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "CellsScreenPreview", heightDp = 900)
@Composable
private fun CellsScreenPreview() {
    LiferychTheme {
        CellsScreen(
            batteryState = previewBatteryState(),
            screenStatus = ScreenUiStatus.Connected,
            onBack = {},
        )
    }
}
