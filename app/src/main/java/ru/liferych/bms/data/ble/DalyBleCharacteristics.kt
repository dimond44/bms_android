package ru.liferych.bms.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import ru.liferych.bms.data.bms.DalyProtocol

/**
 * Detects Daly notify/write characteristics and enables CCCD notifications.
 * No UI dependencies.
 */
object DalyBleCharacteristics {
    private const val TAG = "DalyBle"

    data class Selection(
        val notify: BluetoothGattCharacteristic?,
        val write: BluetoothGattCharacteristic?,
        val debugDump: String,
    )

    fun detect(gatt: BluetoothGatt): Selection {
        var notifyCandidate: BluetoothGattCharacteristic? = null
        var writeCandidate: BluetoothGattCharacteristic? = null

        fun canNotify(ch: BluetoothGattCharacteristic): Boolean {
            val props = ch.properties
            return (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

        fun canWrite(ch: BluetoothGattCharacteristic): Boolean {
            val props = ch.properties
            return (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

        val allChars = mutableListOf<BluetoothGattCharacteristic>()
        for (service in gatt.services) {
            for (ch in service.characteristics) {
                allChars.add(ch)
            }
        }

        for (service in gatt.services) {
            val chars = service.characteristics
            val sameServiceNotify = DalyProtocol.PREFERRED_NOTIFY_UUIDS.firstNotNullOfOrNull { uuid ->
                chars.firstOrNull { it.uuid == uuid && canNotify(it) }
            } ?: chars.firstOrNull { canNotify(it) }
            val sameServiceWrite = DalyProtocol.PREFERRED_WRITE_UUIDS.firstNotNullOfOrNull { uuid ->
                chars.firstOrNull { it.uuid == uuid && canWrite(it) }
            } ?: chars.firstOrNull { canWrite(it) }
            if (sameServiceNotify != null && sameServiceWrite != null) {
                notifyCandidate = sameServiceNotify
                writeCandidate = sameServiceWrite
                break
            }
        }

        if (notifyCandidate == null) {
            for (uuid in DalyProtocol.PREFERRED_NOTIFY_UUIDS) {
                notifyCandidate = allChars.firstOrNull { it.uuid == uuid && canNotify(it) }
                if (notifyCandidate != null) break
            }
        }
        if (notifyCandidate == null) notifyCandidate = allChars.firstOrNull { canNotify(it) }

        if (writeCandidate == null) {
            for (uuid in DalyProtocol.PREFERRED_WRITE_UUIDS) {
                writeCandidate = allChars.firstOrNull { it.uuid == uuid && canWrite(it) }
                if (writeCandidate != null) break
            }
        }
        if (writeCandidate == null) writeCandidate = allChars.firstOrNull { canWrite(it) }

        val sb = StringBuilder()
        sb.appendLine("Selected notify: ${notifyCandidate?.uuid}")
        sb.appendLine("Selected write:  ${writeCandidate?.uuid}")
        sb.appendLine()
        for (service in gatt.services) {
            sb.appendLine("Service: ${service.uuid}")
            for (ch in service.characteristics) {
                sb.appendLine("  Char: ${ch.uuid} props=${ch.properties}")
            }
        }
        val dump = sb.toString()
        Log.i(TAG, dump)
        return Selection(notify = notifyCandidate, write = writeCandidate, debugDump = dump)
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val localEnabled = gatt.setCharacteristicNotification(ch, true)
        Log.i(TAG, "enable notifications uuid=${ch.uuid} local=$localEnabled")
        val descriptor = ch.getDescriptor(DalyProtocol.CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            descriptor.value = if (
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val ok = gatt.writeDescriptor(descriptor)
            Log.i(TAG, "CCCD write started=$ok")
            return ok
        }
        Log.w(TAG, "CCCD descriptor missing for ${ch.uuid}")
        return false
    }

    @SuppressLint("MissingPermission")
    fun writeFrame(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        val props = ch.properties
        ch.writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = frame
        val ok = gatt.writeCharacteristic(ch)
        Log.d(TAG, "write frame ok=$ok hex=${DalyProtocol.bytesToHex(frame)}")
        return ok
    }
}
