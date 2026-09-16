package ru.liferych.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.liferych.bms.R
import ru.liferych.bms.domain.model.CellState
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

@Composable
fun CellVoltageItem(
    cell: CellState,
    minVoltage: Double,
    maxVoltage: Double,
    modifier: Modifier = Modifier,
) {
    val range = (maxVoltage - minVoltage).coerceAtLeast(0.001)
    val normalized = ((cell.voltage - minVoltage) / range).toFloat().coerceIn(0f, 1f)

    LiferychCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ячейка ${cell.index}",
                    style = LiferychTypography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LiferychDimens.CellBarHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiferychColors.BrandYellowSoft),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(normalized)
                            .height(LiferychDimens.CellBarHeight)
                            .background(LiferychColors.BrandYellow),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.3f V".format(cell.voltage),
                    style = LiferychTypography.titleMedium,
                )
                if (cell.balancing) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_liferych_balance),
                            contentDescription = "Балансировка",
                            tint = LiferychColors.Warning,
                            modifier = Modifier.size(LiferychDimens.IconSizeSmall),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Баланс",
                            style = LiferychTypography.labelMedium.copy(
                                color = LiferychColors.Warning,
                            ),
                        )
                    }
                }
            }
        }
    }
}
