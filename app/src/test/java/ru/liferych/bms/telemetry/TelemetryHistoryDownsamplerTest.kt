package ru.liferych.bms.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryHistoryDownsamplerTest {
    @Test
    fun forDisplay_keepsShortSeriesRaw() {
        val points = (0 until 10).map { i ->
            TelemetryHistoryPoint(
                recordedAt = 1_000_000L + i * 15_000L,
                voltage = 13.0 + i * 0.01,
                current = -1.0,
            )
        }
        val out = TelemetryHistoryDownsampler.forDisplay(points, TelemetryHistoryPeriod.H1)
        assertEquals(points.size, out.size)
        assertEquals(points.first().recordedAt, out.first().recordedAt)
    }

    @Test
    fun forDisplay_bucketsLongSeriesWithoutInventingGaps() {
        val points = (0 until 2000).map { i ->
            TelemetryHistoryPoint(
                recordedAt = 1_000_000L + i * 15_000L,
                voltage = 13.2,
                current = if (i % 2 == 0) -5.0 else 5.0,
            )
        }
        val out = TelemetryHistoryDownsampler.forDisplay(points, TelemetryHistoryPeriod.D7)
        assertTrue(out.size < points.size)
        assertTrue(out.size <= TelemetryHistoryPeriod.D7.maxDisplayPoints)
        assertTrue(out.all { it.voltage != null })
    }
}
