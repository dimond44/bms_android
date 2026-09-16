package ru.liferych.bms.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.util.Log
import ru.liferych.bms.data.bms.DalyProtocol
import ru.liferych.bms.data.bms.DalyRxBuffer
import ru.liferych.bms.domain.model.BmsConnectionState
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE transport for Daly BMS: scan/connect helpers, characteristic selection,
 * notification enable, frame writes, and RX byte→frame assembly.
 *
 * Does not contain Android UI. Connection orchestration callbacks stay with the host
 * (legacy MainActivity) during the extraction stage via [Listener].
 */
class DalyBleClient(
    private val adapterProvider: () -> BluetoothAdapter?,
) {
    interface Listener {
        fun onConnectionState(state: BmsConnectionState)
        fun onDalyFrame(frame: ByteArray)
        fun onBleLog(message: String)
    }

    private var listener: Listener? = null
    private val rxBuffer = DalyRxBuffer()
    private val connectionState = AtomicReference<BmsConnectionState>(BmsConnectionState.Disconnected)

    var bluetoothGatt: BluetoothGatt? = null
        private set
    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var bleDebugText: String = ""
        private set

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun currentConnectionState(): BmsConnectionState = connectionState.get()

    fun publishConnectionState(state: BmsConnectionState) {
        connectionState.set(state)
        listener?.onConnectionState(state)
    }

    fun scanner(): BluetoothLeScanner? = adapterProvider()?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    fun startScan(
        callback: ScanCallback,
        filters: List<ScanFilter>? = null,
        settings: ScanSettings? = null,
    ): Boolean {
        val scanner = scanner() ?: return false
        return try {
            if (filters != null && settings != null) {
                scanner.startScan(filters, settings, callback)
            } else if (settings != null) {
                scanner.startScan(emptyList(), settings, callback)
            } else {
                scanner.startScan(callback)
            }
            publishConnectionState(BmsConnectionState.Scanning)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan(callback: ScanCallback) {
        try {
            scanner()?.stopScan(callback)
        } catch (_: Exception) {
            // ignore
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, autoConnect: Boolean, gattCallback: android.bluetooth.BluetoothGattCallback): BluetoothGatt? {
        publishConnectionState(BmsConnectionState.Connecting(device.address))
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(null, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(null, autoConnect, gattCallback)
        }
        bluetoothGatt = gatt
        return gatt
    }

    fun attachGatt(gatt: BluetoothGatt?) {
        bluetoothGatt = gatt
    }

    fun onServicesReady(gatt: BluetoothGatt): Boolean {
        val selection = DalyBleCharacteristics.detect(gatt)
        notifyCharacteristic = selection.notify
        writeCharacteristic = selection.write
        bleDebugText = selection.debugDump
        listener?.onBleLog(selection.debugDump)
        val notify = selection.notify ?: return false
        return DalyBleCharacteristics.enableNotifications(gatt, notify)
    }

    @SuppressLint("MissingPermission")
    fun writeRaw(frame: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val ch = writeCharacteristic ?: return false
        return DalyBleCharacteristics.writeFrame(gatt, ch, frame)
    }

    fun writeCommand(cmd: Int): Boolean = writeRaw(DalyProtocol.buildRequest(cmd))

    /**
     * Feed notification bytes; emits complete Daly frames to [Listener.onDalyFrame].
     */
    fun onNotificationBytes(bytes: ByteArray) {
        val frames = rxBuffer.appendAndExtract(bytes)
        for (frame in frames) {
            listener?.onDalyFrame(frame)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
            // ignore
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
            // ignore
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        rxBuffer.clear()
        publishConnectionState(BmsConnectionState.Disconnected)
    }

    fun clearRx() {
        rxBuffer.clear()
    }

    companion object {
        private const val TAG = "DalyBle"
    }
}
