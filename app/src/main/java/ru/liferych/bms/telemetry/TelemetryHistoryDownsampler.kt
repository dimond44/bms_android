package ru.liferych.bms.telemetry

/**
 * Display-only downsampling. Never mutates Room / server raw samples.
 *
 * Short windows keep raw points (capped). Longer windows use fixed-time
 * buckets and emit one avg point per non-empty bucket so gaps stay visible.
 */
object TelemetryHistoryDownsampler {
    /**
     * @param points ascending raw samples
     * @param period selected UI window
     * @return display points (still real averages of measured samples only)
     */
    fun forDisplay(
        points: List<TelemetryHistoryPoint>,
        period: TelemetryHistoryPeriod,
    ): List<TelemetryHistoryPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size <= period.maxDisplayPoints) return points
        val bucketMs = when (period) {
            TelemetryHistoryPeriod.H1 -> 15_000L
            TelemetryHistoryPeriod.H6 -> 45_000L
            TelemetryHistoryPeriod.H24 -> 120_000L
            TelemetryHistoryPeriod.D7 -> 15L * 60L * 1000L
            TelemetryHistoryPeriod.D30 -> 60L * 60L * 1000L
        }
        return bucketAverage(points, bucketMs, period.maxDisplayPoints)
    }

    /**
     * Averages voltage/current inside each time bucket. Empty buckets are skipped
     * (no invented points).
     */
    private fun bucketAverage(
        points: List<TelemetryHistoryPoint>,
        bucketMs: Long,
        maxPoints: Int,
    ): List<TelemetryHistoryPoint> {
        if (bucketMs <= 0L) return points.takeLast(maxPoints)
        val out = ArrayList<TelemetryHistoryPoint>(maxPoints)
        var i = 0
        while (i < points.size) {
            val start = points[i].recordedAt
            val end = start + bucketMs
            var sumV = 0.0
            var nV = 0
            var sumI = 0.0
            var nI = 0
            var sumTs = 0L
            var n = 0
            while (i < points.size && points[i].recordedAt < end) {
                val p = points[i]
                p.voltage?.let {
                    sumV += it
                    nV += 1
                }
                p.current?.let {
                    sumI += it
                    nI += 1
                }
                sumTs += p.recordedAt
                n += 1
                i += 1
            }
            if (n == 0) continue
            out.add(
                TelemetryHistoryPoint(
                    recordedAt = sumTs / n,
                    voltage = if (nV > 0) sumV / nV else null,
                    current = if (nI > 0) sumI / nI else null,
                ),
            )
        }
        return if (out.size <= maxPoints) out else thinEvenly(out, maxPoints)
    }

    private fun thinEvenly(
        points: List<TelemetryHistoryPoint>,
        maxPoints: Int,
    ): List<TelemetryHistoryPoint> {
        if (points.size <= maxPoints) return points
        val result = ArrayList<TelemetryHistoryPoint>(maxPoints)
        val last = points.lastIndex
        for (k in 0 until maxPoints) {
            val index = ((k.toLong() * last) / (maxPoints - 1)).toInt()
            result.add(points[index])
        }
        return result
    }
}
