package ru.liferych.bms.ui.screens.batteries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.liferych.bms.ui.components.LiferychCard
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.components.StatePlaceholder
import ru.liferych.bms.ui.components.StatusBadge
import ru.liferych.bms.ui.components.StatusTone
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

@Composable
fun BatteriesScreen(
    batteries: List<BatterySummaryUi>,
    onBatteryClick: (BatterySummaryUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LiferychColors.Background,
        topBar = { LiferychTopBar(title = "Мои батареи") },
    ) { padding ->
        if (batteries.isEmpty()) {
            StatePlaceholder(
                icon = Icons.Rounded.BatteryChargingFull,
                title = "Пока нет батарей",
                subtitle = "Добавьте BMS после привязки устройства",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = LiferychDimens.ScreenPadding,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(batteries, key = { it.id }) { battery ->
                    BatteryListItem(
                        battery = battery,
                        onClick = { onBatteryClick(battery) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryListItem(
    battery: BatterySummaryUi,
    onClick: () -> Unit,
) {
    LiferychCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = battery.name, style = LiferychTypography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(text = battery.subtitle, style = LiferychTypography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = battery.connectionLabel,
                        tone = if (battery.isOnline) StatusTone.Success else StatusTone.Neutral,
                    )
                    val soc = battery.socPercent?.let { "%.0f%%".format(it) } ?: "—%"
                    val voltage = battery.voltage?.let { "%.1f V".format(it) } ?: "— V"
                    Text(
                        text = "$soc · $voltage",
                        style = LiferychTypography.labelLarge,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = LiferychColors.IconMuted,
            )
        }
    }
}

@Preview(showBackground = true, name = "BatteriesScreenPreview")
@Composable
private fun BatteriesScreenPreview() {
    LiferychTheme {
        BatteriesScreen(
            batteries = FrontendViewModel.demoBatteries(),
            onBatteryClick = {},
        )
    }
}

@Preview(showBackground = true, name = "BatteriesEmptyPreview")
@Composable
private fun BatteriesEmptyPreview() {
    LiferychTheme {
        BatteriesScreen(
            batteries = emptyList(),
            onBatteryClick = {},
        )
    }
}
