package ru.liferych.bms.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Thin wrapper over FusedLocationProviderClient.
 *
 * One-shot reads only — no continuous high-accuracy listener.
 */
class PhoneLocationProvider(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /**
     * @return true when coarse or fine location is granted.
     */
    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Reads one location fix with timeout. Prefer last known when fresh enough.
     *
     * @return lat/lon/accuracy/time, or null on timeout / missing permission / failure
     */
    suspend fun readOnce(timeoutMs: Long = 12_000L): Fix? {
        if (!hasPermission()) return null
        val last = readLastKnown()
        if (last != null && System.currentTimeMillis() - last.recordedAt <= LAST_KNOWN_MAX_AGE_MS) {
            return last
        }
        return withTimeoutOrNull(timeoutMs) { awaitFreshFix() } ?: last
    }

    @Suppress("MissingPermission")
    private suspend fun readLastKnown(): Fix? = suspendCancellableCoroutine { cont ->
        try {
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (!cont.isActive) return@addOnSuccessListener
                    if (location == null) {
                        cont.resume(null)
                        return@addOnSuccessListener
                    }
                    cont.resume(
                        Fix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                            recordedAt = location.time.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        ),
                    )
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        } catch (_: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun awaitFreshFix(): Fix? = suspendCancellableCoroutine { cont ->
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2_000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                client.removeLocationUpdates(this)
                val location = result.lastLocation
                if (!cont.isActive) return
                if (location == null) {
                    cont.resume(null)
                    return
                }
                cont.resume(
                    Fix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        recordedAt = location.time.takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        cont.invokeOnCancellation {
            client.removeLocationUpdates(callback)
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val recordedAt: Long,
    )

    private companion object {
        private const val LAST_KNOWN_MAX_AGE_MS = 2L * 60L * 1000L
    }
}
