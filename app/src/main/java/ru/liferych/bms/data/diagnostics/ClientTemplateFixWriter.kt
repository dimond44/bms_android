package ru.liferych.bms.data.diagnostics

import ru.liferych.bms.BmsConfigTemplate
import ru.liferych.bms.BmsTemplateParameter
import ru.liferych.bms.data.config.ConfigIoUnavailableException
import ru.liferych.bms.data.config.ConfigRegisterWriter
import ru.liferych.bms.data.config.DalyConfigProtocol
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.data.repository.DalyBmsRepository
import kotlin.math.abs
import kotlin.math.round

/**
 * Compose client template fix — ports MainActivity enqueueClientTemplateWrites /
 * startServiceLocalWrite algorithm (same Modbus frames, delays, writable filter).
 *
 * Does not invent registers: only parameters with a buildable write command are written.
 */
class ClientTemplateFixWriter(
    private val repository: DalyBmsRepository,
    private val configWriter: ConfigRegisterWriter,
    private val diagnosticsRepository: ConfigDiagnosticsRepository,
) {
    /**
     * Live Modbus read of all checkable template registers, then re-evaluate.
     * Used when opening Diagnostics so current values come from BMS, not stale UI.
     */
    suspend fun refreshLiveConfig(
        bmsUid: String,
        battery: BatteryState,
        bluetoothName: String,
    ): DiagnosticsSnapshot {
        if (repository.connectionState.value !is BmsConnectionState.Connected) {
            return diagnosticsRepository.refresh(bmsUid, battery, bluetoothName)
        }
        val base = diagnosticsRepository.refresh(bmsUid, battery, bluetoothName)
        val template = base.template ?: return base
        val family = base.result.hardwareFamily
        val addresses = checkableRegisters(template, family)
        if (addresses.isEmpty()) return base

        try {
            val readback = configWriter.withSession {
                readRegisters(addresses)
            } ?: return base
            val merged = base.registers.toMutableMap()
            merged.putAll(readback)
            diagnosticsRepository.saveRegisters(bmsUid, merged)
            return diagnosticsRepository.refresh(bmsUid, battery, bluetoothName)
        } catch (_: ConfigIoUnavailableException) {
            return base
        }
    }

    /**
     * Writes only mismatched writable parameters, then full reread + re-evaluate.
     *
     * @param bmsUid legacy DL-… uid
     * @param battery live BatteryState for series/HW
     * @param bluetoothName BLE name for cache key
     * @param onProgress UI progress callback (main thread)
     */
    suspend fun applyFix(
        bmsUid: String,
        battery: BatteryState,
        bluetoothName: String,
        onProgress: (String) -> Unit,
    ): ClientFixResult {
        if (repository.connectionState.value !is BmsConnectionState.Connected) {
            return ClientFixResult.Failed("Нет связи с BMS")
        }
        onProgress("Получаем шаблон…")
        val snapshot = refreshLiveConfig(bmsUid, battery, bluetoothName)
        val template = snapshot.template
            ?: return ClientFixResult.Failed("Нет шаблона конфигурации")
        val series = snapshot.result.seriesCount
            ?: ConfigDiagnosticsRepository.resolvedSeriesCount(battery)
            ?: return ClientFixResult.Failed("Не удалось определить число ячеек")
        if (series !in template.supportedSeries) {
            return ClientFixResult.Failed("Нет подходящего шаблона для этой BMS")
        }
        val family = snapshot.result.hardwareFamily
        val registers = snapshot.registers.toMutableMap()
        if (registers.isEmpty()) {
            return ClientFixResult.Failed("Конфигурация BMS ещё не прочитана")
        }

        val commands = buildFixCommands(
            template = template,
            registers = registers,
            series = series,
            hardwareFamily = family,
        )
        if (commands.isEmpty()) {
            return ClientFixResult.Success(snapshot)
        }

        try {
            val readback = configWriter.withSession {
                var done = 0
                val total = commands.size
                for (command in commands) {
                    if (repository.connectionState.value !is BmsConnectionState.Connected) {
                        return@withSession null
                    }
                    done++
                    onProgress("Исправляем конфигурацию…  $done / $total")
                    if (!writeFrames(command.frames)) {
                        return@withSession null
                    }
                    waitFor(LEGACY_PARAMETER_SETTLE_MS)
                }

                onProgress("Проверяем конфигурацию…")
                val readRegs = checkableRegisters(template, family).ifEmpty {
                    commands.map { it.register }.distinct()
                }
                readRegisters(readRegs)
            }
            if (readback == null) {
                return ClientFixResult.Failed(
                    "Запись выполнена, но не удалось перечитать конфигурацию",
                )
            }
            for ((addr, value) in readback) {
                registers[addr] = value
            }
            diagnosticsRepository.saveRegisters(bmsUid, registers)

            val verified = diagnosticsRepository.refresh(bmsUid, battery, bluetoothName)
            return ClientFixResult.Success(verified)
        } catch (_: ConfigIoUnavailableException) {
            return ClientFixResult.Failed("Конфигурационный канал BMS занят")
        }
    }

    private fun checkableRegisters(
        template: BmsConfigTemplate,
        hardwareFamily: String,
    ): List<Int> {
        return template.parameters
            .filter { parameter ->
                parameter.enabled &&
                    parameter.key != "series_cell_count" &&
                    !shouldSkip(parameter, hardwareFamily)
            }
            .map { it.register }
            .distinct()
    }

    private fun buildFixCommands(
        template: BmsConfigTemplate,
        registers: Map<Int, Int>,
        series: Int,
        hardwareFamily: String,
    ): List<FixWriteCommand> {
        val out = mutableListOf<FixWriteCommand>()
        var id = 1
        for (parameter in orderedParameters(template.parameters)) {
            if (!parameter.writable) continue
            if (!parameter.enabled) continue
            if (shouldSkip(parameter, hardwareFamily)) continue
            if (parameter.key == "series_cell_count") continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            if (!registers.containsKey(parameter.register)) continue
            val actual = (registers[parameter.register]!! / parameter.scale) + parameter.offset
            if (abs(actual - expected) <= parameter.tolerance) continue
            val command = buildWriteCommand(id, parameter, expected) ?: continue
            out += command
            id++
        }
        return out
    }

    private fun buildWriteCommand(
        id: Int,
        parameter: BmsTemplateParameter,
        expected: Double,
    ): FixWriteCommand? {
        val raw = round((expected - parameter.offset) * parameter.scale).toInt()
        if (raw < 0 || raw > 0xFFFF) return null
        val frames = writeFrames(parameter, raw)
            ?: listOf(DalyConfigProtocol.writeSingle(0x81, parameter.register, raw))
        return FixWriteCommand(
            id = id,
            key = parameter.key,
            label = parameter.label,
            register = parameter.register,
            rawValue = raw,
            value = expected,
            frames = frames,
        )
    }

    private fun writeFrames(parameter: BmsTemplateParameter, raw: Int): List<ByteArray>? {
        val write = DalyConfigProtocol.writeSingle(0x81, parameter.register, raw)
        return when (parameter.key) {
            "cell_over_voltage" -> {
                val side = (raw - 50).coerceIn(0, 0xFFFF)
                listOf(
                    DalyConfigProtocol.writeSingle(0x81, DALY_CELL_OV_ALARM_REG, side),
                    DalyConfigProtocol.writeSingle(0x81, DALY_CELL_OV_PROTECT_REG, raw),
                    DalyConfigProtocol.writeSingle(0x81, DALY_CELL_OV_RECOVERY_REG, side),
                )
            }
            "soc_calibration_0", "soc_calibration_100" -> listOf(write, write)
            else -> null
        }
    }

    private data class FixWriteCommand(
        val id: Int,
        val key: String,
        val label: String,
        val register: Int,
        val rawValue: Int,
        val value: Double,
        val frames: List<ByteArray>,
    )

    companion object {
        private const val LEGACY_PARAMETER_SETTLE_MS = 700L
        private const val DALY_CELL_OV_ALARM_REG = 0x0130
        private const val DALY_CELL_OV_PROTECT_REG = 0x0131
        private const val DALY_CELL_OV_RECOVERY_REG = 0x0132

        private val WRITE_ORDER = listOf(
            "sleep_timeout",
            "cell_over_voltage",
            "cell_under_voltage",
            "pack_over_voltage",
            "pack_under_voltage",
            "charge_high_temp",
            "charge_low_temp",
            "discharge_high_temp",
            "discharge_low_temp",
            "balance_start_voltage",
            "balance_stop_voltage",
            "balance_delta",
            "soc_calibration_0",
            "soc_calibration_100",
        )

        private val R10K_SKIPPED = setOf(
            "soc_calibration_0",
            "soc_calibration_100",
            "balance_start_voltage",
            "balance_stop_voltage",
            "balance_delta",
        )

        private fun orderedParameters(
            parameters: List<BmsTemplateParameter>,
        ): List<BmsTemplateParameter> {
            return parameters.sortedBy { parameter ->
                val index = WRITE_ORDER.indexOf(parameter.key)
                if (index >= 0) index else WRITE_ORDER.size
            }
        }

        private fun shouldSkip(parameter: BmsTemplateParameter, hardwareFamily: String): Boolean {
            if (hardwareFamily != "standard" && parameter.key in R10K_SKIPPED) return true
            return hardwareFamily in parameter.skipFor
        }

    }
}

sealed class ClientFixResult {
    data class Success(val snapshot: DiagnosticsSnapshot) : ClientFixResult()
    data class Failed(val message: String) : ClientFixResult()
}
