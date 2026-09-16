package ru.liferych.bms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Circular SOC indicator using brand yellow track.
 */
@Composable
fun BatteryIndicator(
    socPercent: Double?,
    modifier: Modifier = Modifier,
    size: Dp = LiferychDimens.BatteryRingSize,
) {
    val progress = ((socPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val valueText = socPercent?.let { "%.0f".format(it) } ?: "—"

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = size.toPx() * 0.08f, cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = LiferychColors.BrandYellowSoft,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
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
                text = valueText,
                style = LiferychTypography.displayLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "% SOC",
                style = LiferychTypography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
