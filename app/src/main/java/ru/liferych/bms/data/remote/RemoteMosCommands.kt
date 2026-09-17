package ru.liferych.bms.data.remote

/**
 * Confirmed Modbus MOS control mapping (real BMS test).
 *
 * Slave 0xD2, FC06 write + FC03 readback, OFF=0 / ON=1.
 * Not part of config template diagnostics.
 */
object RemoteMosCommands {
    const val KEY_CHARGE = "charge_mos"
    const val KEY_DISCHARGE = "discharge_mos"

    const val SLAVE = 0xD2
    const val CHARGE_REGISTER = 0x00A5
    const val DISCHARGE_REGISTER = 0x00A6

    /**
     * @return true when [key] is a trusted MOS remote-control command.
     */
    fun isMosKey(key: String): Boolean =
        key == KEY_CHARGE || key == KEY_DISCHARGE

    /**
     * Trusted register for [key], or null when the key is not MOS.
     */
    fun registerFor(key: String): Int? = when (key) {
        KEY_CHARGE -> CHARGE_REGISTER
        KEY_DISCHARGE -> DISCHARGE_REGISTER
        else -> null
    }

    /**
     * Rejects MOS commands whose server register does not match the trusted map.
     */
    fun matchesTrustedRegister(key: String, register: Int): Boolean =
        registerFor(key) == register
}
