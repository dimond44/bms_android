package ru.liferych.bms

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Простой line-chart для исторической телеметрии (Views / Canvas).
 *
 * @param showZeroLine true для тока (ось 0 A)
 */
class TelemetryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val showZeroLine: Boolean = false
) : View(context, attrs) {

    data class Point(
        val timestamp: Long,
        val value: Double
    )

    private val points = mutableListOf<Point>()
    private var lineColor: Int = Color.rgb(31, 179, 90)
    private var selectedIndex: Int = -1
    private var xFromMs: Long = 0L
    private var xToMs: Long = 0L
    private var onTooltip: ((Point?) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 233, 237)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 186, 194)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 148, 158)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 31, 179, 90)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 119, 129)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.FILL
    }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()
    private val density = resources.displayMetrics.density

    /**
     * Назначение: обновить точки графика и диапазон оси X.
     * @param data точки (уже отсортированы ASC)
     * @param rangeFromMs начало периода (включительно)
     * @param rangeToMs конец периода (включительно)
     * @param color цвет линии
     * Side effects: invalidate UI
     */
    fun setData(
        data: List<Point>,
        rangeFromMs: Long,
        rangeToMs: Long,
        color: Int
    ) {
        points.clear()
        points.addAll(data)
        xFromMs = rangeFromMs
        xToMs = max(rangeToMs, rangeFromMs + 1L)
        lineColor = color
        linePaint.color = color
        markerPaint.color = color
        fillPaint.color = Color.argb(
            28,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        selectedIndex = -1
        onTooltip?.invoke(null)
        invalidate()
    }

    /**
     * Назначение: колбэк tooltip при tap/drag.
     * @param listener null или выбранная точка
     */
    fun setTooltipListener(listener: ((Point?) -> Unit)?) {
        onTooltip = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minH = (220 * density).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(MeasureSpec.getSize(heightMeasureSpec), minH)
            else -> minH
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max(h, minH))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectAtX(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun selectAtX(x: Float) {
        if (points.isEmpty()) {
            selectedIndex = -1
            onTooltip?.invoke(null)
            invalidate()
            return
        }
        val padL = 12f * density
        val padR = 12f * density
        val plotW = (width - padL - padR).coerceAtLeast(1f)
        val ratio = ((x - padL) / plotW).coerceIn(0f, 1f)
        val targetTs = xFromMs + ((xToMs - xFromMs) * ratio).toLong()
        var best = 0
        var bestDist = Long.MAX_VALUE
        for (i in points.indices) {
            val d = abs(points[i].timestamp - targetTs)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        selectedIndex = best
        onTooltip?.invoke(points[best])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = 12f * density
        val padR = 12f * density
        val padT = 16f * density
        val padB = 36f * density
        val left = padL
        val right = width - padR
        val top = padT
        val bottom = height - padB
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)

        // Сетка
        for (i in 0..4) {
            val y = top + plotH * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        drawXLabels(canvas, left, right, bottom + 8f * density)

        if (points.isEmpty()) return

        var minV = points.minOf { it.value }
        var maxV = points.maxOf { it.value }
        if (showZeroLine) {
            minV = min(minV, 0.0)
            maxV = max(maxV, 0.0)
        }
        if (abs(maxV - minV) < 0.05) {
            minV -= 0.5
            maxV += 0.5
        }
        val yPad = (maxV - minV) * 0.08
        minV -= yPad
        maxV += yPad
        val ySpan = (maxV - minV).coerceAtLeast(0.01)

        fun xOf(ts: Long): Float {
            val r = (ts - xFromMs).toDouble() / (xToMs - xFromMs).toDouble()
            return left + (plotW * r.toFloat().coerceIn(0f, 1f))
        }

        fun yOf(v: Double): Float {
            val r = ((v - minV) / ySpan).toFloat().coerceIn(0f, 1f)
            return bottom - plotH * r
        }

        if (showZeroLine && minV < 0.0 && maxV > 0.0) {
            val zy = yOf(0.0)
            canvas.drawLine(left, zy, right, zy, zeroPaint)
        }

        path.reset()
        fillPath.reset()
        for ((i, p) in points.withIndex()) {
            val x = xOf(p.timestamp)
            val y = yOf(p.value)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        if (points.isNotEmpty()) {
            fillPath.lineTo(xOf(points.last().timestamp), bottom)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
        }
        canvas.drawPath(path, linePaint)

        if (selectedIndex in points.indices) {
            val p = points[selectedIndex]
            val x = xOf(p.timestamp)
            val y = yOf(p.value)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawCircle(x, y, 8f * density, markerRing)
            canvas.drawCircle(x, y, 5f * density, markerPaint)
        }
    }

    private fun drawXLabels(canvas: Canvas, left: Float, right: Float, y: Float) {
        val span = xToMs - xFromMs
        val labels = when {
            span <= 26 * 60 * 60 * 1000L -> listOf(0, 4, 8, 12, 16, 20, 24).map { h ->
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = xFromMs
                    set(java.util.Calendar.HOUR_OF_DAY, h.coerceAtMost(23))
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (h >= 24) {
                        add(java.util.Calendar.DAY_OF_MONTH, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                    }
                }
                cal.timeInMillis to "%02d:00".format(if (h >= 24) 0 else h)
            }
            span <= 8L * 24 * 60 * 60 * 1000L -> (0..6).map { i ->
                val ts = xFromMs + span * i / 6
                ts to SimpleDateFormat("dd.MM", Locale("ru")).format(Date(ts))
            }
            else -> (0..4).map { i ->
                val ts = xFromMs + span * i / 4
                ts to SimpleDateFormat("dd.MM", Locale("ru")).format(Date(ts))
            }
        }
        labelPaint.textSize = 11f * density
        for ((ts, text) in labels) {
            val r = ((ts - xFromMs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            val x = left + (right - left) * r
            canvas.drawText(text, x, y + 14f * density, labelPaint)
        }
    }
}
