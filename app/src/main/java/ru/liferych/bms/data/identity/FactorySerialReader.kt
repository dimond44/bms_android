package ru.liferych.bms.data.identity

import android.util.Log
import ru.liferych.bms.data.bms.DalyFactorySerial
import ru.liferych.bms.data.config.ConfigIoUnavailableException
import ru.liferych.bms.data.config.ConfigRegisterWriter
import ru.liferych.bms.data.local.BmsIdentityStore

/**
 * Result of one factory-SN refresh attempt.
 */
sealed class FactorySerialRefresh {
    /** Cache already had SN (or just written). [display] may be empty only if raw was blank. */
    data class Done(val display: String) : FactorySerialRefresh()

    /** Config I/O busy — caller may retry later. */
    data object Busy : FactorySerialRefresh()

    /** BLE read failed or SN invalid after a full register attempt. */
    data object Failed : FactorySerialRefresh()
}

/**
 * Reads factory SN via the existing Daly Modbus path (slave 0xD2, 0x0057–0x005D)
 * and caches it in [BmsIdentityStore] for Compose Dashboard.
 *
 * Does not invent protocol: same registers as MainActivity `dl_sn_0057_005D`.
 */
class FactorySerialReader(
    private val configWriter: ConfigRegisterWriter,
    private val identityStore: BmsIdentityStore,
) {
    /**
     * Ensures [bleAddress] has a cached factory SN when possible.
     *
     * @param bleAddress BLE MAC (identity cache key)
     * @param force when true, re-read even if cache is non-empty
     */
    suspend fun refreshIfNeeded(
        bleAddress: String,
        force: Boolean = false,
    ): FactorySerialRefresh {
        val address = bleAddress.trim()
        if (address.isBlank()) return FactorySerialRefresh.Failed
        if (!force) {
            val cached = identityStore.cachedFactorySerial(address)
            if (!cached.isNullOrBlank()) {
                return FactorySerialRefresh.Done(identityStore.displayFactorySerial(cached))
            }
        }
        Log.i(TAG, "BMS_SERIAL_REQUEST slave=0xD2 regs=0x0057-0x005D")
        val registers = try {
            configWriter.withSession {
                val map = LinkedHashMap<Int, Int>()
                for (addr in DalyFactorySerial.ADDRESSES) {
                    val raw = readRegisterDirect(DalyFactorySerial.SLAVE, addr)
                    if (raw == null) {
                        Log.w(TAG, "BMS_SERIAL_RESPONSE timeout register=0x%04X".format(addr))
                        return@withSession null
                    }
                    map[addr] = raw
                    if (addr != DalyFactorySerial.END) waitFor(READ_GAP_MS)
                }
                map
            }
        } catch (_: ConfigIoUnavailableException) {
            Log.w(TAG, "BMS_SERIAL_RESPONSE unavailable=config_io")
            return FactorySerialRefresh.Busy
        } catch (e: Exception) {
            Log.w(TAG, "BMS_SERIAL_RESPONSE error=${e.javaClass.simpleName}")
            return FactorySerialRefresh.Failed
        }
        if (registers == null) return FactorySerialRefresh.Failed
        val hex = registers.entries.joinToString(" ") { (addr, value) ->
            "0x%04X=0x%04X".format(addr, value)
        }
        Log.i(TAG, "BMS_SERIAL_RESPONSE $hex")
        val sn = DalyFactorySerial.pickValid(registers)
        Log.i(TAG, "BMS_SERIAL_PARSED ok=${sn.isNotBlank()} len=${sn.length}")
        if (sn.isBlank()) return FactorySerialRefresh.Failed
        identityStore.saveFactorySerial(address, sn)
        return FactorySerialRefresh.Done(identityStore.displayFactorySerial(sn))
    }

    private companion object {
        private const val TAG = "FactorySerialReader"
        private const val READ_GAP_MS = 250L
    }
}
