package ru.liferych.bms.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import ru.liferych.bms.data.bms.DalyProtocol
import ru.liferych.bms.data.bms.DalyRxBuffer
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE transport for Daly BMS.
 *
 * Owns BluetoothGatt lifecycle, scan callback, notification stream and writes.
 * Protocol polling / frame apply stay in [ru.liferych.bms.data.repository.DalyBmsRepository].
 */
class DalyBleClient(
    private val appContext: Context,
    private val adapterProvider: () -> BluetoothAdapter?,
    private val mainHandler: Handler,
) {
    interface Host {
        fun onConnectionState(state: BmsConnectionState)
        fun onGattConnected(gatt: BluetoothGatt)
        fun onGattDisconnected()
        fun onServicesFailed(reason: String)
        fun onCharacteristicsReady(gatt: BluetoothGatt)
        fun onNotificationsEnabled()
        fun onNotificationBytes(bytes: ByteArray)
        fun onDeviceFound(device: BmsDevice, bluetoothDevice: BluetoothDevice)
        fun onScanFinished(foundCount: Int)
        fun onBleLog(message: String)
    }

    private var host: Host? = null
    private val rxBuffer = DalyRxBuffer()
    private val connectionState =
        AtomicReference<BmsConnectionState>(BmsConnectionState.Disconnected)

    var bluetoothGatt: BluetoothGatt? = null
        private set
    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var bleDebugText: String = ""
        private set

    private var servicesDiscoveryStarted = false
    private var negotiatedMtu = 23
    private var scanning = false
    private var scanStopToken = 0

    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val scanRssi = linkedMapOf<String, Int>()
    private val scanNames = linkedMapOf<String, String>()

    fun setHost(host: Host?) {
        this.host = host
    }

    fun currentConnectionState(): BmsConnectionState = connectionState.get()

    fun deviceByAddress(address: String): BluetoothDevice? = devices[address]

    fun rememberDevice(device: BluetoothDevice, rssi: Int, name: String?) {
        val address = device.address ?: return
        devices[address] = device
        scanRssi[address] = rssi
        if (!name.isNullOrBlank()) scanNames[address] = name
    }

    private fun publishConnectionState(state: BmsConnectionState) {
        val previous = connectionState.getAndSet(state)
        if (previous != state) {
            Log.i(TAG, "state $previous -> $state")
            host?.onConnectionState(state)
        }
    }

    fun scanner() = adapterProvider()?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = DEFAULT_SCAN_MS): Boolean {
        val scanner = scanner()
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable")
            publishConnectionState(BmsConnectionState.Error("BLE scanner недоступен"))
            return false
        }
        stopScanInternal()
        devices.clear()
        scanRssi.clear()
        scanNames.clear()
        scanning = true
        val token = ++scanStopToken
        publishConnectionState(BmsConnectionState.Scanning)
        return try {
            scanner.startScan(scanCallback)
            mainHandler.postDelayed({
                if (token != scanStopToken) return@postDelayed
                stopScanInternal()
                if (connectionState.get() is BmsConnectionState.Scanning) {
                    publishConnectionState(BmsConnectionState.Disconnected)
                }
                host?.onScanFinished(devices.size)
            }, durationMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
            scanning = false
            publishConnectionState(BmsConnectionState.Error("Scan failed: ${e.message}"))
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanStopToken++
        stopScanInternal()
        if (connectionState.get() is BmsConnectionState.Scanning) {
            publishConnectionState(BmsConnectionState.Disconnected)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        try {
            scanner()?.stopScan(scanCallback)
        } catch (_: Exception) {
            // ignore
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = normalBleName(result) ?: return
            if (!devices.containsKey(address)) {
                devices[address] = device
                scanRssi[address] = result.rssi
                scanNames[address] = name
                host?.onDeviceFound(
                    BmsDevice(address = address, name = name, rssi = result.rssi),
                    device,
                )
            } else {
                scanRssi[address] = result.rssi
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun normalBleName(result: ScanResult): String? {
        val name = result.scanRecord?.deviceName ?: result.device?.name
        val cleaned = name?.trim().orEmpty()
        if (cleaned.isBlank()) return null
        val bad = cleaned.equals("unknown", true) ||
            cleaned.equals("n/a", true) ||
            cleaned.equals("null", true) ||
            cleaned.equals("unnamed", true) ||
            cleaned.equals("без имени", true)
        if (bad) return null
        return cleaned
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        val adapter = adapterProvider()
        if (adapter == null) {
            publishConnectionState(BmsConnectionState.Error("Bluetooth недоступен"))
            return false
        }
        stopScanInternal()
        val device = devices[address] ?: try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.w(TAG, "getRemoteDevice failed: ${e.message}")
            null
        }
        if (device == null) {
            publishConnectionState(BmsConnectionState.Error("Устройство не найдено"))
            return false
        }
        rememberDevice(device, scanRssi[address] ?: 0, scanNames[address] ?: device.name)
        closeGattQuietly()
        servicesDiscoveryStarted = false
        negotiatedMtu = 23
        publishConnectionState(BmsConnectionState.Connecting(address))
        Log.i(TAG, "Connecting to $address")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
        return bluetoothGatt != null
    }

    fun attachGatt(gatt: BluetoothGatt?) {
        bluetoothGatt = gatt
    }

    fun onServicesReady(gatt: BluetoothGatt): Boolean {
        val selection = DalyBleCharacteristics.detect(gatt)
        notifyCharacteristic = selection.notify
        writeCharacteristic = selection.write
        bleDebugText = selection.debugDump
        host?.onBleLog(selection.debugDump)
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

    fun onNotificationBytes(bytes: ByteArray) {
        val frames = rxBuffer.appendAndExtract(bytes)
        for (frame in frames) {
            // Frames are delivered via host notification path in repository.
        }
        // Keep for callers that still feed bytes through client.
        host?.onNotificationBytes(bytes)
    }

    fun extractFrames(bytes: ByteArray): List<ByteArray> = rxBuffer.appendAndExtract(bytes)

    fun clearRx() {
        rxBuffer.clear()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        scanStopToken++
        stopScanInternal()
        closeGattQuietly()
        writeCharacteristic = null
        notifyCharacteristic = null
        rxBuffer.clear()
        publishConnectionState(BmsConnectionState.Disconnected)
    }

    @SuppressLint("MissingPermission")
    private fun closeGattQuietly() {
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
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection address=${gatt.device.address} status=$status state=$newState")
            // Ignore stale callbacks from a GATT that was already replaced/closed.
            if (bluetoothGatt !== gatt) {
                Log.w(TAG, "ignore stale GATT callback address=${gatt.device.address}")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected")
                bluetoothGatt = gatt
                servicesDiscoveryStarted = false
                negotiatedMtu = 23
                rxBuffer.clear()
                host?.onGattConnected(gatt)
                val mtuRequested =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                        gatt.requestMtu(247)
                Log.i(TAG, "request MTU 247 started=$mtuRequested")
                if (!mtuRequested) {
                    discoverGattServicesOnce(gatt)
                } else {
                    mainHandler.postDelayed({ discoverGattServicesOnce(gatt) }, 1500)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected status=$status")
                writeCharacteristic = null
                notifyCharacteristic = null
                rxBuffer.clear()
                host?.onGattDisconnected()
                if (bluetoothGatt === gatt) {
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                    bluetoothGatt = null
                }
                publishConnectionState(BmsConnectionState.Disconnected)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(TAG, "MTU changed mtu=$mtu status=$status")
            discoverGattServicesOnce(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "services discovered status=$status count=${gatt.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                host?.onServicesFailed("Не удалось прочитать BLE-сервисы: $status")
                gatt.disconnect()
                return
            }
            val ok = onServicesReady(gatt)
            if (!ok || writeCharacteristic == null || notifyCharacteristic == null) {
                host?.onServicesFailed("Не найдены BLE характеристики write/notify")
                return
            }
            Log.i(TAG, "Notifications enable requested")
            host?.onCharacteristicsReady(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.i(TAG, "CCCD write uuid=${descriptor.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                host?.onServicesFailed("BMS не разрешила получение данных: $status")
                return
            }
            host?.onNotificationsEnabled()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            Log.d(TAG, "notify ${characteristic.uuid}: ${DalyProtocol.bytesToHex(bytes)}")
            host?.onNotificationBytes(bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.d(TAG, "notify ${characteristic.uuid}: ${DalyProtocol.bytesToHex(value)}")
            host?.onNotificationBytes(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverGattServicesOnce(gatt: BluetoothGatt) {
        if (servicesDiscoveryStarted || bluetoothGatt !== gatt) return
        servicesDiscoveryStarted = true
        val started = gatt.discoverServices()
        Log.i(TAG, "discover services started=$started mtu=$negotiatedMtu")
    }

    companion object {
        private const val TAG = "DalyBle"
        const val DEFAULT_SCAN_MS = 8000L
    }
}
