package ru.liferych.bms.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaversineTest {
    @Test
    fun haversine_knownShortDistance() {
        // ~111 m north from equator reference (approx)
        val meters = haversineMeters(0.0, 0.0, 0.001, 0.0)
        assertTrue(meters in 100.0..120.0)
    }

    @Test
    fun haversine_samePointIsZero() {
        assertEquals(0.0, haversineMeters(55.75, 37.61, 55.75, 37.61), 0.01)
    }
}
