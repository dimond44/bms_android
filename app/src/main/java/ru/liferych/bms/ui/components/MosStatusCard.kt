package ru.liferych.bms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ru.liferych.bms.R
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Client-facing MOS status (read-only). Not an engineering control panel.
 */
@Composable
fun MosStatusCard(
    chargeEnabled: Boolean?,
    dischargeEnabled: Boolean?,
    modifier: Modifier = Modifier,
) {
    LiferychCard(modifier = modifier) {
        Text(text = "Управление батареей", style = LiferychTypography.titleMedium)
        Spacer(Modifier.height(LiferychDimens.Space4))
        Text(
            text = "Состояние зарядного и разрядного ключа",
            style = LiferychTypography.bodySmall,
        )
        Spacer(Modifier.height(LiferychDimens.Space16))
        MosStatusLine(
            iconRes = R.drawable.ic_liferych_charge,
            label = "Заряд",
            enabled = chargeEnabled,
        )
        Spacer(Modifier.height(LiferychDimens.Space12))
        MosStatusLine(
            iconRes = R.drawable.ic_liferych_discharge,
            label = "Разряд",
            enabled = dischargeEnabled,
        )
    }
}

@Composable
private fun MosStatusLine(
    iconRes: Int,
    label: String,
    enabled: Boolean?,
) {
    val value = when (enabled) {
        true -> "Включён"
        false -> "Выключен"
        null -> "—"
    }
    val tone = when (enabled) {
        true -> StatusTone.Success
        false -> StatusTone.Neutral
        null -> StatusTone.Neutral
    }
    val iconTint = when (enabled) {
        true -> LiferychColors.Success
        false -> LiferychColors.IconDefault
        null -> LiferychColors.IconMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space8),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(LiferychDimens.MetricIconSize),
            )
            Text(text = label, style = LiferychTypography.bodyLarge)
        }
        StatusBadge(text = value, tone = tone)
    }
}

@Preview(showBackground = true, name = "MosStatusCardPreview")
@Composable
private fun MosStatusCardPreview() {
    LiferychTheme {
        MosStatusCard(
            chargeEnabled = true,
            dischargeEnabled = true,
            modifier = Modifier,
        )
    }
}
