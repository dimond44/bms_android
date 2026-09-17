package ru.liferych.bms.ui.screens.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.telemetry.TelemetryHistoryPeriod
import ru.liferych.bms.telemetry.TelemetryHistoryPoint
import ru.liferych.bms.telemetry.TelemetryHistoryRepository
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One series plotted against a shared time window.
 */
enum class HistorySeriesKind {
    Voltage,
    Current,
}

/**
 * Inspect marker shown after tap/drag.
 */
data class HistoryInspectPoint(
    val recordedAt: Long,
    val value: Double,
    val label: String,
)

/**
 * Dual-axis-ready line chart with grid, ticks, gaps, and inspect marker.
 */
@Composable
fun HistoryLineChart(
    title: String,
    unit: String,
    kind: HistorySeriesKind,
    points: List<TelemetryHistoryPoint>,
    fromMs: Long,
    toMs: Long,
    period: TelemetryHistoryPeriod,
    seriesMin: Double?,
    seriesMax: Double?,
    seriesAvg: Double?,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    var inspect by remember(points, kind) { mutableStateOf<HistoryInspectPoint?>(null) }
    val density = LocalDensity.current
    val plotHeight = 168.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = LiferychTypography.titleMedium, color = LiferychColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildStatsLine(seriesMin, seriesMax, seriesAvg, unit, kind),
            style = LiferychTypography.labelMedium,
            color = LiferychColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight)
                .pointerInput(points, fromMs, toMs, kind) {
                    detectTapGestures { offset ->
                        inspect = nearestPoint(
                            points = points,
                            kind = kind,
                            fromMs = fromMs,
                            toMs = toMs,
                            x = offset.x,
                            width = size.width.toFloat(),
                            leftPad = with(density) { 44.dp.toPx() },
                            rightPad = with(density) { 8.dp.toPx() },
                            unit = unit,
                        )
                    }
                }
                .pointerInput(points, fromMs, toMs, kind) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            inspect = nearestPoint(
                                points = points,
                                kind = kind,
                                fromMs = fromMs,
                                toMs = toMs,
                                x = change.position.x,
                                width = size.width.toFloat(),
                                leftPad = with(density) { 44.dp.toPx() },
                                rightPad = with(density) { 8.dp.toPx() },
                                unit = unit,
                            )
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val leftPad = 44.dp.toPx()
                val rightPad = 8.dp.toPx()
                val topPad = 8.dp.toPx()
                val bottomPad = 28.dp.toPx()
                val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val plotLeft = leftPad
                val plotTop = topPad
                val plotBottom = plotTop + plotH

                val values = points.mapNotNull { valueOf(it, kind) }
                val (yMin, yMax) = niceYRange(values, kind)
                val yTicks = niceTicks(yMin, yMax, targetCount = 5)
                val xTicks = timeTicks(fromMs, toMs, period)

                val gridPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 232, 236, 240)
                    strokeWidth = 1f
                    isAntiAlias = true
                }
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(111, 119, 129)
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }

                // Horizontal grid + Y labels
                for (tick in yTicks) {
                    val y = plotBottom - ((tick - yMin) / (yMax - yMin).coerceAtLeast(1e-9) * plotH).toFloat()
                    drawContext.canvas.nativeCanvas.drawLine(
                        plotLeft,
                        y,
                        plotLeft + plotW,
                        y,
                        gridPaint,
                    )
                    val label = formatYTick(tick, kind)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        0f,
                        y + labelPaint.textSize * 0.35f,
                        labelPaint,
                    )
                }

                // Vertical grid + X labels
                for (tick in xTicks) {
                    val xFrac = ((tick - fromMs).toDouble() / (toMs - fromMs).coerceAtLeast(1L).toDouble())
                        .coerceIn(0.0, 1.0)
                    val x = plotLeft + (xFrac * plotW).toFloat()
                    drawContext.canvas.nativeCanvas.drawLine(
                        x,
                        plotTop,
                        x,
                        plotBottom,
                        gridPaint,
                    )
                    val label = formatXTick(tick, period)
                    val textW = labelPaint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        (x - textW / 2f).coerceIn(plotLeft, plotLeft + plotW - textW),
                        size.height - 4.dp.toPx(),
                        labelPaint,
                    )
                }

                // Zero line for current
                if (kind == HistorySeriesKind.Current && yMin < 0.0 && yMax > 0.0) {
                    val zeroY = plotBottom -
                        ((0.0 - yMin) / (yMax - yMin).coerceAtLeast(1e-9) * plotH).toFloat()
                    drawLine(
                        color = LiferychColors.TextSecondary.copy(alpha = 0.45f),
                        start = Offset(plotLeft, zeroY),
                        end = Offset(plotLeft + plotW, zeroY),
                        strokeWidth = 1.5f,
                    )
                }

                // Series path with gap breaks
                if (points.size >= 2) {
                    val span = (toMs - fromMs).coerceAtLeast(1L).toDouble()
                    var path: Path? = null
                    var prevTs: Long? = null
                    for (point in points) {
                        val value = valueOf(point, kind) ?: continue
                        val x = plotLeft +
                            (((point.recordedAt - fromMs).toDouble() / span).coerceIn(0.0, 1.0) * plotW).toFloat()
                        val y = plotBottom -
                            (((value - yMin) / (yMax - yMin).coerceAtLeast(1e-9)) * plotH).toFloat()
                        val broken = prevTs != null &&
                            point.recordedAt - prevTs!! > TelemetryHistoryRepository.GAP_THRESHOLD_MS
                        if (path == null || broken) {
                            path?.let {
                                drawPath(
                                    path = it,
                                    color = lineColor,
                                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                                )
                            }
                            path = Path().also { it.moveTo(x, y) }
                        } else {
                            path.lineTo(x, y)
                        }
                        prevTs = point.recordedAt
                    }
                    path?.let {
                        drawPath(
                            path = it,
                            color = lineColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                        )
                    }
                }

                // Inspect marker
                inspect?.let { marker ->
                    val span = (toMs - fromMs).coerceAtLeast(1L).toDouble()
                    val x = plotLeft +
                        (((marker.recordedAt - fromMs).toDouble() / span).coerceIn(0.0, 1.0) * plotW).toFloat()
                    val y = plotBottom -
                        (((marker.value - yMin) / (yMax - yMin).coerceAtLeast(1e-9)) * plotH).toFloat()
                    drawLine(
                        color = LiferychColors.BrandYellowDark.copy(alpha = 0.7f),
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = 1.5f,
                    )
                    drawCircle(color = lineColor, radius = 5f, center = Offset(x, y))
                    drawCircle(color = Color.White, radius = 2.5f, center = Offset(x, y))
                }
            }
        }
        inspect?.let { marker ->
            Text(
                text = marker.label,
                style = LiferychTypography.labelMedium,
                color = LiferychColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun valueOf(point: TelemetryHistoryPoint, kind: HistorySeriesKind): Double? {
    return when (kind) {
        HistorySeriesKind.Voltage -> point.voltage
        HistorySeriesKind.Current -> point.current
    }
}

private fun buildStatsLine(
    min: Double?,
    max: Double?,
    avg: Double?,
    unit: String,
    kind: HistorySeriesKind,
): String {
    fun fmt(v: Double?): String {
        if (v == null) return "—"
        return when (kind) {
            HistorySeriesKind.Voltage -> String.format(Locale.US, "%.2f", v)
            HistorySeriesKind.Current -> String.format(Locale.US, "%+.1f", v)
        }
    }
    return "Мин ${fmt(min)} $unit · Макс ${fmt(max)} $unit · Сред ${fmt(avg)} $unit"
}

private fun nearestPoint(
    points: List<TelemetryHistoryPoint>,
    kind: HistorySeriesKind,
    fromMs: Long,
    toMs: Long,
    x: Float,
    width: Float,
    leftPad: Float,
    rightPad: Float,
    unit: String,
): HistoryInspectPoint? {
    if (points.isEmpty()) return null
    val plotW = (width - leftPad - rightPad).coerceAtLeast(1f)
    val frac = ((x - leftPad) / plotW).coerceIn(0f, 1f)
    val targetTs = fromMs + ((toMs - fromMs) * frac).toLong()
    var best: TelemetryHistoryPoint? = null
    var bestDist = Long.MAX_VALUE
    for (point in points) {
        if (valueOf(point, kind) == null) continue
        val dist = abs(point.recordedAt - targetTs)
        if (dist < bestDist) {
            bestDist = dist
            best = point
        }
    }
    val hit = best ?: return null
    val value = valueOf(hit, kind) ?: return null
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(hit.recordedAt))
    val valueText = when (kind) {
        HistorySeriesKind.Voltage -> String.format(Locale.US, "%.2f %s", value, unit)
        HistorySeriesKind.Current -> String.format(Locale.US, "%+.1f %s", value, unit)
    }
    return HistoryInspectPoint(
        recordedAt = hit.recordedAt,
        value = value,
        label = "$time · $valueText",
    )
}

private fun niceYRange(values: List<Double>, kind: HistorySeriesKind): Pair<Double, Double> {
    if (values.isEmpty()) {
        return when (kind) {
            HistorySeriesKind.Voltage -> 12.0 to 14.0
            HistorySeriesKind.Current -> -10.0 to 10.0
        }
    }
    var min = values.minOrNull() ?: 0.0
    var max = values.maxOrNull() ?: 0.0
    if (kind == HistorySeriesKind.Current) {
        if (min > 0) min = 0.0
        if (max < 0) max = 0.0
    }
    if (abs(max - min) < 1e-6) {
        val pad = when (kind) {
            HistorySeriesKind.Voltage -> 0.1
            HistorySeriesKind.Current -> 1.0
        }
        min -= pad
        max += pad
    } else {
        val pad = (max - min) * 0.08
        min -= pad
        max += pad
    }
    return niceFloor(min, max) to niceCeil(min, max)
}

private fun niceTicks(min: Double, max: Double, targetCount: Int): List<Double> {
    val span = (max - min).coerceAtLeast(1e-9)
    val step = niceStep(span / targetCount.coerceAtLeast(2))
    val start = ceil(min / step) * step
    val ticks = ArrayList<Double>()
    var v = start
    var guard = 0
    while (v <= max + step * 0.5 && guard < 20) {
        ticks.add(v)
        v += step
        guard += 1
    }
    if (ticks.isEmpty()) ticks.add(min)
    return ticks
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val exp = floor(log10(raw))
    val base = 10.0.pow(exp)
    val frac = raw / base
    val niceFrac = when {
        frac <= 1.0 -> 1.0
        frac <= 2.0 -> 2.0
        frac <= 5.0 -> 5.0
        else -> 10.0
    }
    return niceFrac * base
}

private fun niceFloor(min: Double, max: Double): Double {
    val step = niceStep((max - min) / 5.0)
    return floor(min / step) * step
}

private fun niceCeil(min: Double, max: Double): Double {
    val step = niceStep((max - min) / 5.0)
    return ceil(max / step) * step
}

private fun timeTicks(fromMs: Long, toMs: Long, period: TelemetryHistoryPeriod): List<Long> {
    val step = when (period) {
        TelemetryHistoryPeriod.H1 -> 15L * 60L * 1000L
        TelemetryHistoryPeriod.H6 -> 60L * 60L * 1000L
        TelemetryHistoryPeriod.H24 -> 4L * 60L * 60L * 1000L
        TelemetryHistoryPeriod.D7 -> 24L * 60L * 60L * 1000L
        TelemetryHistoryPeriod.D30 -> 5L * 24L * 60L * 60L * 1000L
    }
    val ticks = ArrayList<Long>()
    var t = ((fromMs + step - 1) / step) * step
    var guard = 0
    while (t <= toMs && guard < 12) {
        ticks.add(t)
        t += step
        guard += 1
    }
    if (ticks.isEmpty()) {
        ticks.add(fromMs)
        ticks.add(toMs)
    }
    return ticks.distinct()
}

private fun formatYTick(value: Double, kind: HistorySeriesKind): String {
    return when (kind) {
        HistorySeriesKind.Voltage -> String.format(Locale.US, "%.1f", value)
        HistorySeriesKind.Current -> {
            val rounded = (value * 10.0).roundToInt() / 10.0
            if (abs(rounded) < 1e-6) "0" else String.format(Locale.US, "%+.0f", rounded)
        }
    }
}

private fun formatXTick(ts: Long, period: TelemetryHistoryPeriod): String {
    val date = Date(ts)
    return when (period) {
        TelemetryHistoryPeriod.H1,
        TelemetryHistoryPeriod.H6,
        TelemetryHistoryPeriod.H24,
        -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        TelemetryHistoryPeriod.D7,
        TelemetryHistoryPeriod.D30,
        -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
    }
}
