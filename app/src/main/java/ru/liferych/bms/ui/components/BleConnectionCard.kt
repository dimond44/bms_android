package ru.liferych.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ru.liferych.bms.R
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Compact BLE + last-update footer for Dashboard.
 */
@Composable
fun BleConnectionCard(
    connectionState: BmsConnectionState,
    lastUpdateLabel: String,
    modifier: Modifier = Modifier,
) {
    val (label, tone) = connectionState.toStatusLabel()
    val dotColor = when (tone) {
        StatusTone.Success -> LiferychColors.Success
        StatusTone.Warning -> LiferychColors.Warning
        StatusTone.Error -> LiferychColors.Error
        StatusTone.Neutral -> LiferychColors.IconMuted
    }
    val bluetoothTint = when (tone) {
        StatusTone.Success -> LiferychColors.Success
        StatusTone.Warning -> LiferychColors.Warning
        StatusTone.Error -> LiferychColors.Error
        StatusTone.Neutral -> LiferychColors.IconDefault
    }

    LiferychCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space12),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_liferych_bluetooth),
                contentDescription = "Bluetooth",
                tint = bluetoothTint,
                modifier = Modifier.size(LiferychDimens.IconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Bluetooth", style = LiferychTypography.labelMedium)
                Spacer(Modifier.height(LiferychDimens.Space4))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(LiferychDimens.Space8)
                            .background(dotColor, CircleShape),
                    )
                    Text(text = label, style = LiferychTypography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(LiferychDimens.Space16))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space12),
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = LiferychColors.IconMuted,
                modifier = Modifier.size(LiferychDimens.IconSize),
            )
            Column {
                Text(text = "Обновлено", style = LiferychTypography.labelMedium)
                Spacer(Modifier.height(LiferychDimens.Space4))
                Text(text = lastUpdateLabel, style = LiferychTypography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true, name = "BleConnectionCardPreview")
@Composable
private fun BleConnectionCardPreview() {
    LiferychTheme {
        BleConnectionCard(
            connectionState = BmsConnectionState.Connected("AA:BB:CC:DD:EE:01"),
            lastUpdateLabel = "16.08.2024 12:00",
        )
    }
}
