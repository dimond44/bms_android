package ru.liferych.bms

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.liferych.bms.data.ble.DalyBleClient
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.domain.repository.BmsRepository

/**
 * Application-level runtime container: one BLE client + one [BmsRepository].
 * Avoids multiple Gatt connections across Activity recreation / dual UI.
 */
class AppContainer(
    appContext: Context,
) {
    private val app = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    val bleClient: DalyBleClient = DalyBleClient(
        appContext = app,
        adapterProvider = {
            val manager = app.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            manager?.adapter
        },
        mainHandler = mainHandler,
    )

    val bmsRepository: DalyBmsRepository = DalyBmsRepository(
        appContext = app,
        bleClient = bleClient,
        mainHandler = mainHandler,
    )

    /** Domain-facing alias for Compose / ViewModels. */
    val repository: BmsRepository get() = bmsRepository
}
