package ru.liferych.bms.data.location

import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SharedPreferences for location permission prompt state.
 */
class BatteryLocationPrefs(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** User tapped «Не сейчас» — do not auto-prompt again. */
    var promptDeclined: Boolean
        get() = prefs.getBoolean(KEY_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_DECLINED, value).apply()

    /** Prompt was already shown at least once this install. */
    var promptShown: Boolean
        get() = prefs.getBoolean(KEY_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOWN, value).apply()

    private companion object {
        private const val PREFS = "battery_location_prefs"
        private const val KEY_DECLINED = "prompt_declined"
        private const val KEY_SHOWN = "prompt_shown"
    }
}

/**
 * Great-circle distance in meters (WGS84 sphere approx).
 */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earth * c
}
