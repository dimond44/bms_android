package ru.liferych.bms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography
import kotlin.math.max
import kotlin.math.min

/**
 * Port of legacy [ru.liferych.bms.SocGaugeView] for Compose.
 * Arc color: red ≤20, amber &lt;70, green otherwise.
 */
@Composable
fun SocGauge(
    socPercent: Double?,
    modifier: Modifier = Modifier,
    size: Dp = LiferychDimens.SocRingSize,
) {
    val s = max(0.0, min(100.0, socPercent ?: 0.0))
    val progress = if (socPercent == null) 0f else (s / 100.0).toFloat()
    val accent = when {
        socPercent == null -> LiferychColors.SocTrack
        s <= 20.0 -> LiferychColors.Error
        s < 70.0 -> LiferychColors.Warning
        else -> LiferychColors.Success
    }
    val percentText = if (socPercent == null) "--%" else "%.0f%%".format(s)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidth = (size.toPx() * 0.095f).coerceIn(18f, 26f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val pad = strokeWidth / 2f + 4f
            val diam = this.size.minDimension - 2f * pad
            val left = (this.size.width - this.size.minDimension) / 2f + pad
            val top = (this.size.height - this.size.minDimension) / 2f + pad
            val topLeft = Offset(left, top)
            val arcSize = Size(diam, diam)
            drawArc(
                color = LiferychColors.SocTrack,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            if (socPercent != null && progress > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = percentText,
                color = LiferychColors.TextPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "SOC",
                style = LiferychTypography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun socStatusLabel(soc: Double?): Pair<String, Color> {
    val s = soc ?: return "Нет данных" to LiferychColors.TextSecondary
    return when {
        s <= 20.0 -> "Низкий уровень заряда" to LiferychColors.Error
        s < 70.0 -> "Средний уровень заряда" to LiferychColors.Warning
        else -> "Батарея заряжена" to LiferychColors.SuccessAlt
    }
}
