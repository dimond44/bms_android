package ru.liferych.bms.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.telemetry.TelemetryBmsUid

/**
 * Application-level remote write poller, independent of Activity lifecycle.
 *
 * One instance is owned by AppContainer. It starts after Daly wake completes
 * and stops on disconnect/reconnect/runtime shutdown.
 */
class RemoteWriteCoordinator(
    private val repository: DalyBmsRepository,
    private val api: RemoteWriteApi,
    private val executor: RemoteWriteExecutor,
    private val enabled: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DalyBmsRepository.RuntimeListener {
    private val commandMutex = Mutex()
    private var started = false
    private var sessionToken = 0L
    private var activeBmsUid: String? = null
    private var pollingJob: Job? = null
    private var immediatePollJob: Job? = null

    /**
     * Attaches this singleton coordinator to repository runtime events.
     * Repeated calls are idempotent.
     */
    @Synchronized
    fun start() {
        if (!enabled || started) return
        started = true
        repository.addRuntimeListener(this)
    }

    /**
     * Stops polling, detaches listeners, and cancels application jobs.
     */
    @Synchronized
    fun close() {
        if (!started) return
        started = false
        stopActiveSession()
        repository.removeRuntimeListener(this)
        scope.cancel()
    }

    /**
     * Starts exactly one poll loop for an awake Daly runtime.
     */
    override fun onRuntimeReady(address: String, bluetoothName: String) {
        if (!enabled || !started) return
        val uid = TelemetryBmsUid.resolve(address, bluetoothName)
        if (!TelemetryBmsUid.isStable(uid, address)) {
            Log.w(TAG, "poll not started: unstable bms_uid")
            return
        }
        stopActiveSession()
        activeBmsUid = uid
        val token = ++sessionToken
        Log.i(TAG, "poll started bms_uid=$uid")
        pollingJob = scope.launch {
            delay(LEGACY_INITIAL_POLL_DELAY_MS)
            while (isActive && isCurrentSession(uid, token)) {
                pollOnce(uid, token)
                delay(LEGACY_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Cancels polling and active execution when GATT runtime stops.
     */
    override fun onRuntimeStopped() {
        if (!enabled) return
        Log.i(TAG, "poll stopped bms_uid=${activeBmsUid.orEmpty()}")
        stopActiveSession()
    }

    /**
     * Requests a guarded one-shot fetch, used by legacy config-read completion.
     * Existing execution mutex prevents overlap with the periodic poller.
     */
    fun requestImmediatePoll(delayMs: Long = 0L) {
        if (!enabled || !started || activeBmsUid == null) return
        val uid = activeBmsUid ?: return
        val token = sessionToken
        immediatePollJob?.cancel()
        immediatePollJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (isCurrentSession(uid, token)) pollOnce(uid, token)
        }
    }

    /**
     * Fetches and executes only commands[0], with a single-execution guard.
     */
    private suspend fun pollOnce(uid: String, token: Long) {
        if (!isCurrentSession(uid, token)) return
        if (!commandMutex.tryLock()) {
            Log.d(TAG, "fetch skipped: execution busy")
            return
        }
        try {
            if (!isCurrentSession(uid, token)) return
            Log.d(TAG, "fetch bms_uid=$uid")
            val command = try {
                api.fetchFirstPending(uid)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "fetch failed: ${error.message}")
                return
            } ?: return

            Log.i(TAG, "command=${command.id}/${command.key} register=${command.registerText}")
            if (command.bmsUid != uid || !isCurrentSession(uid, token)) {
                Log.w(TAG, "command target mismatch command=${command.id}")
                return
            }

            Log.i(TAG, "execution started command=${command.id} key=${command.key}")
            val result = executor.execute(
                command = command,
                currentBmsUid = uid,
                onWriting = {
                    api.acknowledge(
                        bmsUid = uid,
                        commandId = command.id,
                        ack = RemoteWriteAck(status = STATUS_WRITING),
                    )
                },
            )
            if (!isCurrentSession(uid, token)) return
            when (result) {
                is RemoteWriteExecutionResult.Done -> {
                    Log.i(TAG, "done command=${command.id} key=${command.key}")
                    api.acknowledge(
                        bmsUid = uid,
                        commandId = command.id,
                        ack = RemoteWriteAck(
                            status = STATUS_DONE,
                            actual = result.actual,
                        ),
                    )
                    scheduleNextCommand()
                }
                is RemoteWriteExecutionResult.Failed -> {
                    Log.w(
                        TAG,
                        "failed command=${command.id} key=${command.key} error=${result.error}",
                    )
                    api.acknowledge(
                        bmsUid = uid,
                        commandId = command.id,
                        ack = RemoteWriteAck(
                            status = STATUS_FAILED,
                            actual = result.actual,
                            error = result.error,
                        ),
                    )
                    scheduleNextCommand()
                }
                RemoteWriteExecutionResult.Deferred -> {
                    Log.d(TAG, "command deferred command=${command.id}")
                }
                RemoteWriteExecutionResult.TargetMismatch -> {
                    Log.w(TAG, "command rejected: wrong BMS command=${command.id}")
                }
            }
        } finally {
            commandMutex.unlock()
        }
    }

    /**
     * Preserves legacy fast chaining after one command completes.
     */
    private fun scheduleNextCommand() {
        requestImmediatePoll(LEGACY_NEXT_COMMAND_DELAY_MS)
    }

    /**
     * Checks that no disconnect/reconnect changed the active BMS session.
     */
    @Synchronized
    private fun isCurrentSession(uid: String, token: Long): Boolean {
        return started && activeBmsUid == uid && sessionToken == token
    }

    /**
     * Cancels all jobs tied to the current GATT session.
     */
    @Synchronized
    private fun stopActiveSession() {
        sessionToken++
        activeBmsUid = null
        immediatePollJob?.cancel()
        immediatePollJob = null
        pollingJob?.cancel()
        pollingJob = null
    }

    private companion object {
        private const val TAG = "RemoteWrite"
        private const val LEGACY_INITIAL_POLL_DELAY_MS = 1_500L
        private const val LEGACY_POLL_INTERVAL_MS = 8_000L
        private const val LEGACY_NEXT_COMMAND_DELAY_MS = 1_200L
        private const val STATUS_WRITING = "writing"
        private const val STATUS_DONE = "done"
        private const val STATUS_FAILED = "failed"
    }
}
