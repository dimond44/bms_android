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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.liferych.bms.R
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.ui.components.LiferychCard
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.components.StatePlaceholder
import ru.liferych.bms.ui.components.StatusBadge
import ru.liferych.bms.ui.components.StatusTone
import ru.liferych.bms.ui.devicesearch.sanitizeBleDisplayName
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

@Composable
fun BatteriesScreen(
    batteries: List<BatterySummaryUi>,
    connectionState: BmsConnectionState,
    onBatteryClick: (BatterySummaryUi) -> Unit,
    onRefreshScan: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val scanning = connectionState is BmsConnectionState.Scanning
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LiferychColors.Background,
        topBar = {
            LiferychTopBar(
                title = "Поиск BMS",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LiferychDimens.ScreenPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        scanning -> "Сканирование…"
                        batteries.isEmpty() -> "Устройства не найдены"
                        else -> "Найдено: ${batteries.size}"
                    },
                    style = LiferychTypography.bodyMedium,
                    color = LiferychColors.TextSecondary,
                )
                TextButton(onClick = onRefreshScan) {
                    Text(text = if (scanning) "Обновить" else "Сканировать")
                }
            }

            if (batteries.isEmpty()) {
                StatePlaceholder(
                    iconRes = R.drawable.ic_liferych_bluetooth,
                    title = if (scanning) "Ищем BMS поблизости" else "Нет устройств",
                    subtitle = if (scanning) {
                        "Убедитесь, что батарея включена"
                    } else {
                        "Нажмите «Сканировать»"
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
            Icon(
                painter = painterResource(R.drawable.ic_liferych_bluetooth),
                contentDescription = null,
                tint = LiferychColors.IconDefault,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sanitizeBleDisplayName(battery.name)
                        .ifBlank { battery.address ?: battery.id },
                    style = LiferychTypography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = battery.subtitle, style = LiferychTypography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                StatusBadge(
                    text = battery.connectionLabel,
                    tone = if (battery.isOnline) StatusTone.Success else StatusTone.Neutral,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_liferych_ok),
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
            connectionState = BmsConnectionState.Scanning,
            onBatteryClick = {},
            onRefreshScan = {},
        )
    }
}
