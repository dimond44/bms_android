package ru.liferych.bms

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.liferych.bms.data.auth.AuthBatteriesStore
import ru.liferych.bms.data.auth.AuthSessionStore
import ru.liferych.bms.data.auth.ProfileAvatarFiles
import ru.liferych.bms.data.auth.UserAuthApi
import ru.liferych.bms.data.auth.UserAuthRepositoryImpl
import ru.liferych.bms.data.ble.DalyBleClient
import ru.liferych.bms.data.config.ConfigRegisterWriter
import ru.liferych.bms.data.local.BmsIdentityStore
import ru.liferych.bms.data.location.BatteryLocationApi
import ru.liferych.bms.data.location.BatteryLocationCoordinator
import ru.liferych.bms.data.location.BatteryLocationPrefs
import ru.liferych.bms.data.location.PhoneLocationProvider
import ru.liferych.bms.data.remote.RemoteWriteApi
import ru.liferych.bms.data.remote.RemoteWriteCoordinator
import ru.liferych.bms.data.remote.RemoteWriteExecutor
import ru.liferych.bms.data.local.SavedBatteriesStore
import ru.liferych.bms.data.repository.DalyBmsRepository
import ru.liferych.bms.data.support.SupportApi
import ru.liferych.bms.data.support.SupportLocalCache
import ru.liferych.bms.data.support.SupportMediaEncoder
import ru.liferych.bms.data.support.SupportRepositoryImpl
import ru.liferych.bms.domain.auth.AuthRepository
import ru.liferych.bms.domain.repository.BmsRepository
import ru.liferych.bms.domain.support.SupportRepository

/**
 * Application-level runtime container: BLE + auth + support repositories.
 * Avoids multiple Gatt connections across Activity recreation / dual UI.
 */
class AppContainer(
    appContext: Context,
) {
    private val app = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Same SharedPreferences as legacy MainActivity «Мои батареи». */
    val savedBatteriesStore: SavedBatteriesStore = SavedBatteriesStore(app)

    /** Same `bms_identity` prefs as legacy MainActivity (factory SN / HW cache). */
    val bmsIdentityStore: BmsIdentityStore = BmsIdentityStore(app)

    /** Same `user_profile` prefs as legacy MainActivity session. */
    val authSessionStore: AuthSessionStore = AuthSessionStore(app)

    val userAuthApi: UserAuthApi = UserAuthApi()

    /**
     * CLIENT auth/profile repository (phone identity + app API key).
     * Single instance for Compose ViewModels.
     */
    val authRepository: AuthRepository = UserAuthRepositoryImpl(
        sessionStore = authSessionStore,
        api = userAuthApi,
        batteriesStore = AuthBatteriesStore { items -> savedBatteriesStore.save(items) },
        avatarFiles = ProfileAvatarFiles(app),
    )

    val supportApi: SupportApi = SupportApi()
    val supportLocalCache: SupportLocalCache = SupportLocalCache(app)
    val supportMediaEncoder: SupportMediaEncoder = SupportMediaEncoder(app)

    /** CLIENT warranty/support repository (single instance). */
    val supportRepository: SupportRepository = SupportRepositoryImpl(
        api = supportApi,
        cache = supportLocalCache,
        mediaEncoder = supportMediaEncoder,
    )

    val bleClient: DalyBleClient = DalyBleClient(
        appContext = app,
        adapterProvider = {
            val manager = app.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            manager?.adapter
        },
        mainHandler = mainHandler,
    )

    /**
     * Shared runtime telemetry uploader (Room + batch sync).
     * Used by DalyBmsRepository so Compose FrontendActivity uploads without MainActivity.
     */
    val runtimeTelemetryUploader: ru.liferych.bms.telemetry.RuntimeTelemetryUploader =
        ru.liferych.bms.telemetry.RuntimeTelemetryUploader(
            context = app,
            identityStore = bmsIdentityStore,
            authSessionStore = authSessionStore,
        )

    val bmsRepository: DalyBmsRepository = DalyBmsRepository(
        appContext = app,
        bleClient = bleClient,
        mainHandler = mainHandler,
        telemetryUploader = runtimeTelemetryUploader,
    )

    /** Domain-facing alias for Compose / ViewModels. */
    val repository: BmsRepository get() = bmsRepository

    /** Shared exclusive FC06/FC03 config-register transport. */
    val configRegisterWriter: ConfigRegisterWriter = ConfigRegisterWriter(bmsRepository)

    /** Existing remote write-command API. */
    val remoteWriteApi: RemoteWriteApi = RemoteWriteApi()

    /** Legacy-compatible read/write/readback executor. */
    val remoteWriteExecutor: RemoteWriteExecutor = RemoteWriteExecutor(
        repository = bmsRepository,
        configWriter = configRegisterWriter,
    )

    /**
     * Application-level remote command poller; disabled for service flavor,
     * matching legacy MainActivity semantics.
     */
    val remoteWriteCoordinator: RemoteWriteCoordinator = RemoteWriteCoordinator(
        repository = bmsRepository,
        api = remoteWriteApi,
        executor = remoteWriteExecutor,
        enabled = !BuildConfig.IS_SERVICE,
    )

    /** Phone GPS → server while a BMS is connected (no background permission). */
    val locationCoordinator: BatteryLocationCoordinator = BatteryLocationCoordinator(
        repository = bmsRepository,
        provider = PhoneLocationProvider(app),
        api = BatteryLocationApi(),
        prefs = BatteryLocationPrefs(app),
    )

    /** Legacy config-template diagnostics (server template + config register cache). */
    val configDiagnosticsRepository:
        ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository =
        ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository(app)

    /** Client template fix (legacy Modbus write + readback). */
    val clientTemplateFixWriter:
        ru.liferych.bms.data.diagnostics.ClientTemplateFixWriter =
        ru.liferych.bms.data.diagnostics.ClientTemplateFixWriter(
            repository = bmsRepository,
            configWriter = configRegisterWriter,
            diagnosticsRepository = configDiagnosticsRepository,
        )

    init {
        remoteWriteCoordinator.start()
        locationCoordinator.start()
    }
}
