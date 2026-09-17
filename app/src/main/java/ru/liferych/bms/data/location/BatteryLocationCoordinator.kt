package ru.liferych.bms.data.location

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.domain.model.BatteryLocation
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.telemetry.TelemetryBmsUid

/**
 * Foreground-only phone location while a BMS runtime is connected.
 *
 * Observes [DalyBmsRepository.RuntimeListener]: starts on awake, stops on disconnect.
 * Does not request ACCESS_BACKGROUND_LOCATION.
 */
class BatteryLocationCoordinator(
    private val repository: DalyBmsRepository,
    private val provider: PhoneLocationProvider,
    private val api: BatteryLocationApi,
    private val prefs: BatteryLocationPrefs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DalyBmsRepository.RuntimeListener {
    private var started = false
    private var sessionToken = 0L
    private var activeBmsUid: String? = null
    private var loopJob: Job? = null

    private var lastSentLat: Double? = null
    private var lastSentLon: Double? = null
    private var lastSentAt: Long = 0L

    private val _permissionPrompt = MutableStateFlow(false)
    /** True when UI should show the one-shot location explanation dialog. */
    val permissionPrompt: StateFlow<Boolean> = _permissionPrompt.asStateFlow()

    /**
     * Attaches to repository runtime events. Idempotent.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        repository.addRuntimeListener(this)
    }

    /**
     * Detaches and cancels jobs.
     */
    @Synchronized
    fun close() {
        if (!started) return
        started = false
        stopSession()
        repository.removeRuntimeListener(this)
        scope.cancel()
    }

    override fun onRuntimeReady(address: String, bluetoothName: String) {
        if (!started) return
        val uid = TelemetryBmsUid.resolve(address, bluetoothName)
        if (!TelemetryBmsUid.isStable(uid, address)) {
            Log.w(TAG, "location not started: unstable bms_uid")
            return
        }
        stopSession()
        activeBmsUid = uid
        lastSentLat = null
        lastSentLon = null
        lastSentAt = 0L
        val token = ++sessionToken
        Log.i(TAG, "location session start bms_uid=$uid")
        maybeRequestPermissionPrompt()
        loopJob = scope.launch {
            // Wait for first fresh telemetry before first fix.
            waitForFreshTelemetry(uid, token)
            if (!isCurrentSession(uid, token)) return@launch
            collectOnce(uid, token, force = true)
            while (isActive && isCurrentSession(uid, token)) {
                delay(POLL_INTERVAL_MS)
                if (!isCurrentSession(uid, token)) break
                if (repository.connectionState.value !is BmsConnectionState.Connected) break
                collectOnce(uid, token, force = false)
            }
        }
    }

    override fun onRuntimeStopped() {
        if (!started) return
        Log.i(TAG, "location session stop bms_uid=${activeBmsUid.orEmpty()}")
        stopSession()
    }

    /**
     * Called after the user grants location permission from the in-app prompt.
     */
    fun onPermissionGranted() {
        _permissionPrompt.value = false
        prefs.promptShown = true
        val uid = activeBmsUid ?: return
        val token = sessionToken
        scope.launch {
            collectOnce(uid, token, force = true)
        }
    }

    /**
     * User chose «Не сейчас».
     */
    fun onPermissionDeclined() {
        _permissionPrompt.value = false
        prefs.promptShown = true
        prefs.promptDeclined = true
    }

    /**
     * Clears the prompt flag without declining (e.g. dialog dismissed).
     */
    fun dismissPermissionPrompt() {
        _permissionPrompt.value = false
    }

    private fun maybeRequestPermissionPrompt() {
        if (provider.hasPermission()) return
        if (prefs.promptDeclined || prefs.promptShown) return
        prefs.promptShown = true
        _permissionPrompt.value = true
    }

    private suspend fun waitForFreshTelemetry(uid: String, token: Long) {
        repeat(40) {
            if (!isCurrentSession(uid, token)) return
            if (hasFreshTelemetry()) return
            delay(500L)
        }
    }

    private fun hasFreshTelemetry(): Boolean {
        if (repository.connectionState.value !is BmsConnectionState.Connected) return false
        val updatedAt = repository.batteryState.value.lastUpdatedAt ?: return false
        return System.currentTimeMillis() - updatedAt <= FRESH_TELEMETRY_MAX_AGE_MS
    }

    private suspend fun collectOnce(uid: String, token: Long, force: Boolean) {
        if (!isCurrentSession(uid, token)) return
        if (repository.connectionState.value !is BmsConnectionState.Connected) return
        if (!hasFreshTelemetry()) return
        if (!provider.hasPermission()) return

        val now = System.currentTimeMillis()
        if (!force && lastSentAt > 0L && now - lastSentAt < MIN_INTERVAL_MS) {
            return
        }

        val fix = provider.readOnce() ?: return
        if (!isCurrentSession(uid, token)) return
        if (!isValidCoordinate(fix.latitude, fix.longitude)) return

        val moved = when {
            lastSentLat == null || lastSentLon == null -> true
            else -> haversineMeters(
                lastSentLat!!,
                lastSentLon!!,
                fix.latitude,
                fix.longitude,
            ) >= MIN_DISTANCE_M
        }
        if (!force && !moved && now - lastSentAt < MIN_INTERVAL_MS) return
        // After interval, still send even if stationary (keeps "last known" fresh).
        if (!force && !moved && now - lastSentAt < FORCE_REFRESH_MS) return

        val location = BatteryLocation(
            bmsUid = uid,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            recordedAt = fix.recordedAt,
        )
        val ok = api.postLocation(location)
        if (ok) {
            lastSentLat = fix.latitude
            lastSentLon = fix.longitude
            lastSentAt = now
            Log.i(
                TAG,
                "Location update sent bmsUid=$uid accuracy=${fix.accuracyMeters}",
            )
        }
    }

    private fun stopSession() {
        loopJob?.cancel()
        loopJob = null
        activeBmsUid = null
        _permissionPrompt.value = false
    }

    private fun isCurrentSession(uid: String, token: Long): Boolean {
        return started && activeBmsUid == uid && sessionToken == token
    }

    private companion object {
        private const val TAG = "BatteryLocation"
        private const val FRESH_TELEMETRY_MAX_AGE_MS = 45_000L
        private const val POLL_INTERVAL_MS = 60_000L
        private const val MIN_INTERVAL_MS = 5L * 60L * 1000L
        private const val FORCE_REFRESH_MS = 15L * 60L * 1000L
        private const val MIN_DISTANCE_M = 75.0

        private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
            if (!lat.isFinite() || !lon.isFinite()) return false
            if (lat < -90.0 || lat > 90.0) return false
            if (lon < -180.0 || lon > 180.0) return false
            return !(lat == 0.0 && lon == 0.0)
        }
    }
}
