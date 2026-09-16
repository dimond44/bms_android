package ru.liferych.bms.data.bms

import java.util.UUID

/**
 * Daly A5 request/response protocol helpers.
 * Behaviour must stay identical to the legacy MainActivity implementation.
 */
object DalyProtocol {
    const val FRAME_START: Byte = 0xA5.toByte()
    const val REQUEST_ADDRESS: Byte = 0x40
    const val DATA_LEN: Byte = 0x08
    const val FRAME_SIZE: Int = 13

    const val CMD_VOLTAGE_CURRENT_SOC: Int = 0x90
    const val CMD_CELL_EXTREMES: Int = 0x91
    const val CMD_TEMP_EXTREMES: Int = 0x92
    const val CMD_MOS_REMAINING: Int = 0x93
    const val CMD_STATUS: Int = 0x94
    const val CMD_CELL_VOLTAGES: Int = 0x95
    const val CMD_TEMPERATURES: Int = 0x96
    const val CMD_BALANCING: Int = 0x97
    const val CMD_ERRORS: Int = 0x98

    const val DALY_BATTERY_CODE_CMD: Int = 0x57
    const val DALY_BATTERY_CODE_FRAMES: Int = 5
    const val DALY_HW_VERSION_CMD: Int = 0x63
    const val DALY_HW_VERSION_FRAMES: Int = 5

    val RUNTIME_POLL_COMMANDS: List<Int> = listOf(
        CMD_VOLTAGE_CURRENT_SOC,
        CMD_CELL_EXTREMES,
        CMD_TEMP_EXTREMES,
        CMD_MOS_REMAINING,
        CMD_STATUS,
        CMD_CELL_VOLTAGES,
        CMD_TEMPERATURES,
        CMD_BALANCING,
        CMD_ERRORS,
    )

    val CHAR_UUID_CANDIDATES: Set<UUID> = setOf(
        UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb"),
    )

    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val PREFERRED_NOTIFY_UUIDS: List<UUID> = listOf(
        UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
    )

    val PREFERRED_WRITE_UUIDS: List<UUID> = listOf(
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb"),
    )

    fun buildRequest(cmd: Int): ByteArray = buildA5Frame(cmd, ByteArray(8))

    fun buildA5Frame(cmd: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = FRAME_START
        frame[1] = REQUEST_ADDRESS
        frame[2] = cmd.toByte()
        frame[3] = DATA_LEN
        for (i in 0 until 8) {
            frame[4 + i] = if (i < payload.size) payload[i] else 0
        }
        frame[12] = checksum(frame, 12)
        return frame
    }

    fun checksum(bytes: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    fun isValidFrame(frame: ByteArray): Boolean {
        return frame.size == FRAME_SIZE &&
            frame[0] == FRAME_START &&
            checksum(frame, 12) == frame[12]
    }

    fun u16(bytes: ByteArray, off: Int): Int {
        return ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
    }

    fun u32(bytes: ByteArray, off: Int): Long {
        return ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)
    }

    fun hex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { b ->
            (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }

    /**
     * Official Daly 0x98 battery failure bit descriptions.
     * Copied verbatim from legacy MainActivity — do not “fix” without protocol proof.
     */
    fun errorDescription(globalBit: Int): String {
        return when (globalBit) {
            0 -> "Напряжение ячейки высокое, уровень 1"
            1 -> "Напряжение ячейки высокое, уровень 2"
            2 -> "Напряжение ячейки низкое, уровень 1"
            3 -> "Напряжение ячейки низкое, уровень 2"
            4 -> "Общее напряжение высокое, уровень 1"
            5 -> "Общее напряжение высокое, уровень 2"
            6 -> "Общее напряжение низкое, уровень 1"
            7 -> "Общее напряжение низкое, уровень 2"
            8 -> "Температура зарядки высокая, уровень 1"
            9 -> "Температура зарядки высокая, уровень 2"
            10 -> "Температура зарядки низкая, уровень 1"
            11 -> "Температура зарядки низкая, уровень 2"
            12 -> "Температура разрядки высокая, уровень 1"
            13 -> "Температура разрядки высокая, уровень 2"
            14 -> "Температура разрядки низкая, уровень 1"
            15 -> "Температура разрядки низкая, уровень 2"
            16 -> "Ток зарядки превышен, уровень 1"
            17 -> "Ток зарядки превышен, уровень 2"
            18 -> "Ток разрядки превышен, уровень 1"
            19 -> "Ток разрядки превышен, уровень 2"
            20 -> "SOC высокий, уровень 1"
            21 -> "SOC высокий, уровень 2"
            22 -> "SOC низкий, уровень 1"
            23 -> "SOC низкий, уровень 2"
            24 -> "Разбег напряжений ячеек, уровень 1"
            25 -> "Разбег напряжений ячеек, уровень 2"
            26 -> "Разбег температур, уровень 1"
            27 -> "Разбег температур, уровень 2"
            28 -> "Резерв Byte3 Bit4"
            29 -> "Резерв Byte3 Bit5"
            30 -> "Резерв Byte3 Bit6"
            31 -> "Резерв Byte3 Bit7"
            32 -> "Температура MOS зарядки высокая"
            33 -> "Температура MOS разрядки высокая"
            34 -> "Ошибка датчика температуры MOS зарядки"
            35 -> "Ошибка датчика температуры MOS разрядки"
            36 -> "Залипание MOS зарядки"
            37 -> "Залипание MOS разрядки"
            38 -> "Обрыв MOS зарядки"
            39 -> "Обрыв MOS разрядки"
            40 -> "Ошибка AFE / чипа измерения"
            41 -> "Отвалился сбор напряжения"
            42 -> "Ошибка датчика температуры ячеек"
            43 -> "Ошибка EEPROM"
            44 -> "Ошибка RTC"
            45 -> "Ошибка предзаряда"
            46 -> "Ошибка связи"
            47 -> "Ошибка внутренней связи"
            48 -> "Ошибка токового модуля"
            49 -> "Ошибка измерения общего напряжения"
            50 -> "Защита от короткого замыкания"
            51 -> "Запрет зарядки из-за низкого напряжения"
            52 -> "Резерв Byte6 Bit4"
            53 -> "Резерв Byte6 Bit5"
            54 -> "Резерв Byte6 Bit6"
            55 -> "Резерв Byte6 Bit7"
            56 -> "Дополнительный fault code Byte7 Bit0"
            57 -> "Дополнительный fault code Byte7 Bit1"
            58 -> "Дополнительный fault code Byte7 Bit2"
            59 -> "Дополнительный fault code Byte7 Bit3"
            60 -> "Дополнительный fault code Byte7 Bit4"
            61 -> "Дополнительный fault code Byte7 Bit5"
            62 -> "Дополнительный fault code Byte7 Bit6"
            63 -> "Дополнительный fault code Byte7 Bit7"
            else -> "Неизвестная ошибка Daly"
        }
    }
}
