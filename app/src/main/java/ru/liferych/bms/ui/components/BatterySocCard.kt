package ru.liferych.bms.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Primary SOC hero card for Dashboard — compact ring + context, not a giant number alone.
 */
@Composable
fun BatterySocCard(
    socPercent: Double?,
    voltage: Double?,
    statusLabel: String,
    statusTone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val progress = ((socPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val socText = socPercent?.let { "%.0f".format(it) } ?: "—"
    val voltageText = voltage?.let { "%.1f В".format(it) } ?: "— В"

    LiferychCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space8),
            ) {
                Box(
                    modifier = Modifier
                        .size(LiferychDimens.MetricIconWell)
                        .background(LiferychColors.BrandYellowSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryFull,
                        contentDescription = null,
                        tint = LiferychColors.BrandYellowDark,
                        modifier = Modifier.size(LiferychDimens.MetricIconSize),
                    )
                }
                Text(text = "Заряд", style = LiferychTypography.titleMedium)
            }
            StatusBadge(text = statusLabel, tone = statusTone)
        }

        Spacer(Modifier.height(LiferychDimens.Space16))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LiferychDimens.SocRingSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(LiferychDimens.SocRingSize)) {
                val strokeWidth = size.minDimension * 0.09f
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                val inset = strokeWidth / 2f
                val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
                val topLeft = Offset(inset, inset)
                // Soft track
                drawArc(
                    color = LiferychColors.BrandYellowSoft,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                // Brand progress
                drawArc(
                    color = LiferychColors.BrandYellow,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = socText,
                    style = LiferychTypography.displayLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "%",
                    style = LiferychTypography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = LiferychColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(LiferychDimens.Space12))

        Text(
            text = voltageText,
            style = LiferychTypography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LiferychDimens.Space4))
        Text(
            text = "Напряжение батареи",
            style = LiferychTypography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, name = "BatterySocCardPreview")
@Composable
private fun BatterySocCardPreview() {
    LiferychTheme {
        BatterySocCard(
            socPercent = 78.0,
            voltage = 51.8,
            statusLabel = "Разряд",
            statusTone = StatusTone.Neutral,
            modifier = Modifier.padding(LiferychDimens.Space16),
        )
    }
}
