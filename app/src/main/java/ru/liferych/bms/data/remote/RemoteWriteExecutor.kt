package ru.liferych.bms.data.remote

import android.util.Log
import ru.liferych.bms.data.config.ConfigIoUnavailableException
import ru.liferych.bms.data.config.ConfigIoUnavailableReason
import ru.liferych.bms.data.config.ConfigRegisterWriter
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.domain.model.BmsConnectionState
import kotlin.math.abs

/**
 * Executes one server command through shared Daly Modbus I/O.
 *
 * Config parameters use slave 0x81 + unlock. MOS commands use confirmed
 * slave 0xD2 / A5-A6 without unlock. Success always requires FC03 readback.
 */
class RemoteWriteExecutor(
    private val repository: DalyBmsRepository,
    private val configWriter: ConfigRegisterWriter,
) {
    /**
     * Executes [command] only for [currentBmsUid].
     *
     * [onWriting] runs after an initial already-matches read and immediately
     * before the physical write, preserving legacy ACK ordering.
     */
    suspend fun execute(
        command: RemoteWriteCommand,
        currentBmsUid: String,
        onWriting: suspend () -> Unit,
    ): RemoteWriteExecutionResult {
        if (command.bmsUid != currentBmsUid) {
            Log.w(
                TAG,
                "target mismatch command=${command.id} expected=$currentBmsUid actual=${command.bmsUid}",
            )
            return RemoteWriteExecutionResult.TargetMismatch
        }
        if (repository.connectionState.value !is BmsConnectionState.Connected) {
            return RemoteWriteExecutionResult.Deferred
        }
        if (RemoteMosCommands.isMosKey(command.key)) {
            return executeMos(command, onWriting)
        }
        return executeConfigParameter(command, onWriting)
    }

    /**
     * Confirmed MOS path: FC03 optional already-match, FC06 write, FC03 verify.
     * Discrete 0/1 only — no engineering tolerance.
     */
    private suspend fun executeMos(
        command: RemoteWriteCommand,
        onWriting: suspend () -> Unit,
    ): RemoteWriteExecutionResult {
        if (!RemoteMosCommands.matchesTrustedRegister(command.key, command.register)) {
            Log.w(
                TAG_MOS,
                "rejected untrusted register command=${command.id} key=${command.key} " +
                    "register=${command.registerText}",
            )
            return RemoteWriteExecutionResult.Failed(
                actual = null,
                error = ERROR_INVALID_MOS_REGISTER,
            )
        }
        if (command.rawValue !in 0..1) {
            return RemoteWriteExecutionResult.Failed(
                actual = null,
                error = ERROR_INVALID_MOS_VALUE,
            )
        }
        val mosKind = if (command.key == RemoteMosCommands.KEY_CHARGE) "CHARGE" else "DISCHARGE"
        val requested = if (command.rawValue == 1) "ON" else "OFF"

        return try {
            configWriter.withSession {
                val initialRaw = readRegisterDirect(
                    slave = RemoteMosCommands.SLAVE,
                    register = command.register,
                )
                Log.i(
                    TAG_MOS,
                    "command=${command.id} bms_uid=${command.bmsUid} $mosKind " +
                        "requested=$requested current=${formatMosState(initialRaw)}",
                )
                if (initialRaw == command.rawValue) {
                    Log.i(TAG_MOS, "already matches command=${command.id} actual=$requested")
                    return@withSession RemoteWriteExecutionResult.Done(command.rawValue.toDouble())
                }

                onWriting()
                Log.i(
                    TAG_MOS,
                    "write command=${command.id} $mosKind $requested " +
                        "slave=0x%02X register=${command.registerText}".format(RemoteMosCommands.SLAVE),
                )
                if (!writeSingleRegisterDirect(
                        slave = RemoteMosCommands.SLAVE,
                        register = command.register,
                        rawValue = command.rawValue,
                    )
                ) {
                    return@withSession RemoteWriteExecutionResult.Failed(
                        actual = initialRaw?.toDouble(),
                        error = ERROR_BLE_WRITE_FAILED,
                    )
                }
                waitFor(MOS_WRITE_SETTLE_MS)

                val actualRaw = readRegisterDirect(
                    slave = RemoteMosCommands.SLAVE,
                    register = command.register,
                )
                val actualLabel = formatMosState(actualRaw)
                Log.i(
                    TAG_MOS,
                    "verify command=${command.id} $mosKind requested=$requested actual=$actualLabel",
                )
                if (actualRaw == command.rawValue) {
                    Log.i(TAG_MOS, "done command=${command.id} $mosKind actual=$actualLabel")
                    RemoteWriteExecutionResult.Done(actualRaw.toDouble())
                } else {
                    Log.w(TAG_MOS, "failed command=${command.id} $mosKind actual=$actualLabel")
                    RemoteWriteExecutionResult.Failed(
                        actual = actualRaw?.toDouble(),
                        error = ERROR_NOT_CONFIRMED,
                    )
                }
            }
        } catch (error: ConfigIoUnavailableException) {
            when (error.reason) {
                ConfigIoUnavailableReason.Busy,
                ConfigIoUnavailableReason.Disconnected,
                -> RemoteWriteExecutionResult.Deferred
            }
        }
    }

    /**
     * Legacy config-parameter path (slave 0x81 + unlock + tolerance verify).
     */
    private suspend fun executeConfigParameter(
        command: RemoteWriteCommand,
        onWriting: suspend () -> Unit,
    ): RemoteWriteExecutionResult {
        return try {
            configWriter.withSession {
                val initialRaw = readRegister(command.register)
                val initial = RemoteWriteVerifier.verify(
                    command = command,
                    raw = initialRaw,
                    runtimeSoc = repository.batteryState.value.soc,
                )
                if (initial.confirmed) {
                    Log.i(TAG, "already matches command=${command.id} key=${command.key}")
                    return@withSession RemoteWriteExecutionResult.Done(initial.actual)
                }

                onWriting()
                Log.i(
                    TAG,
                    "write command=${command.id} key=${command.key} " +
                        "register=${command.registerText}",
                )
                if (!writeSingleRegister(command.register, command.rawValue)) {
                    return@withSession RemoteWriteExecutionResult.Failed(
                        actual = initial.actual,
                        error = ERROR_BLE_WRITE_FAILED,
                    )
                }
                waitFor(LEGACY_WRITE_SETTLE_MS)

                var lastVerification = RemoteWriteVerification(false, null)
                for (attempt in 0..LEGACY_VERIFY_RETRIES) {
                    val raw = readRegister(command.register)
                    lastVerification = RemoteWriteVerifier.verify(
                        command = command,
                        raw = raw,
                        runtimeSoc = repository.batteryState.value.soc,
                    )
                    Log.i(
                        TAG,
                        "verify command=${command.id} key=${command.key} " +
                            "attempt=$attempt confirmed=${lastVerification.confirmed}",
                    )
                    if (lastVerification.confirmed) {
                        return@withSession RemoteWriteExecutionResult.Done(
                            lastVerification.actual,
                        )
                    }
                    if (attempt < LEGACY_VERIFY_RETRIES) {
                        waitFor(LEGACY_VERIFY_RETRY_DELAY_MS)
                    }
                }
                RemoteWriteExecutionResult.Failed(
                    actual = lastVerification.actual,
                    error = ERROR_NOT_CONFIRMED,
                )
            }
        } catch (error: ConfigIoUnavailableException) {
            when (error.reason) {
                ConfigIoUnavailableReason.Busy,
                ConfigIoUnavailableReason.Disconnected,
                -> RemoteWriteExecutionResult.Deferred
            }
        }
    }

    private companion object {
        private const val TAG = "RemoteWrite"
        private const val TAG_MOS = "RemoteMos"
        private const val LEGACY_WRITE_SETTLE_MS = 900L
        private const val LEGACY_VERIFY_RETRIES = 2
        private const val LEGACY_VERIFY_RETRY_DELAY_MS = 700L
        private const val MOS_WRITE_SETTLE_MS = 200L
        private const val ERROR_BLE_WRITE_FAILED = "ble_write_failed"
        private const val ERROR_NOT_CONFIRMED = "not_confirmed"
        private const val ERROR_INVALID_MOS_REGISTER = "invalid_mos_register"
        private const val ERROR_INVALID_MOS_VALUE = "invalid_mos_value"

        private fun formatMosState(raw: Int?): String = when (raw) {
            1 -> "ON"
            0 -> "OFF"
            null -> "unknown"
            else -> "raw=$raw"
        }
    }
}

/**
 * Pure legacy verification rules, isolated for unit testing.
 */
internal object RemoteWriteVerifier {
    /**
     * Verifies raw/engineering values exactly like MainActivity.
     */
    fun verify(
        command: RemoteWriteCommand,
        raw: Int?,
        runtimeSoc: Double?,
    ): RemoteWriteVerification {
        if (RemoteMosCommands.isMosKey(command.key)) {
            val confirmed = raw != null && raw == command.rawValue
            return RemoteWriteVerification(
                confirmed = confirmed,
                actual = raw?.toDouble(),
            )
        }
        val actualFromRegister = raw?.let {
            (it.toDouble() / command.scale) + command.offset
        }
        val actual = if (command.key == "runtime_soc") {
            runtimeSoc ?: actualFromRegister
        } else {
            actualFromRegister
        }
        val confirmed = if (command.key == "runtime_soc") {
            when {
                raw != null && raw == command.rawValue -> true
                actualFromRegister != null &&
                    abs(actualFromRegister - command.value) <= RUNTIME_SOC_TOLERANCE -> true
                runtimeSoc != null &&
                    abs(runtimeSoc - command.value) <= RUNTIME_SOC_TOLERANCE -> true
                else -> false
            }
        } else {
            when {
                raw == null -> false
                raw == command.rawValue -> true
                else -> {
                    val lsb = 1.0 / abs(command.scale)
                    val tolerance = (lsb / 2.0).coerceAtMost(MAX_VALUE_TOLERANCE)
                    actualFromRegister != null &&
                        abs(actualFromRegister - command.value) <= tolerance
                }
            }
        }
        return RemoteWriteVerification(confirmed = confirmed, actual = actual)
    }

    private const val RUNTIME_SOC_TOLERANCE = 1.0
    private const val MAX_VALUE_TOLERANCE = 0.02
}

internal data class RemoteWriteVerification(
    val confirmed: Boolean,
    val actual: Double?,
)

sealed class RemoteWriteExecutionResult {
    data class Done(val actual: Double?) : RemoteWriteExecutionResult()
    data class Failed(val actual: Double?, val error: String) : RemoteWriteExecutionResult()
    data object Deferred : RemoteWriteExecutionResult()
    data object TargetMismatch : RemoteWriteExecutionResult()
}
