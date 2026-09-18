package ru.liferych.bms.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liferych.bms.BuildConfig
import ru.liferych.bms.data.auth.ProfileAvatarEncoder
import ru.liferych.bms.data.local.BmsIdentityStore
import ru.liferych.bms.data.local.SavedBattery
import ru.liferych.bms.data.local.SavedBatteriesStore
import ru.liferych.bms.domain.auth.AuthRepository
import ru.liferych.bms.domain.auth.AuthResult
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.auth.UserProfile
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.repository.BmsRepository
import ru.liferych.bms.ui.batteries.SavedBatteryPresence
import ru.liferych.bms.ui.devicesearch.deviceUiDisplayName
import ru.liferych.bms.ui.devicesearch.sanitizeBleDisplayName
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.model.ProfileAuthFeedback
import ru.liferych.bms.ui.model.ProfileAvatarUi
import ru.liferych.bms.ui.model.ProfileUi
import ru.liferych.bms.ui.model.SavedBatteryCardStatus
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.model.toScreenUiStatus

/**
 * ViewModel for the new Compose client frontend.
 * Composables observe this VM — never touch repositories / stores directly.
 */
class FrontendViewModel(
    private val repository: BmsRepository,
    private val savedBatteriesStore: SavedBatteriesStore,
    private val bmsIdentityStore: BmsIdentityStore,
    private val authRepository: AuthRepository,
    private val avatarEncoder: ProfileAvatarEncoder? = null,
    private val configDiagnosticsRepository:
        ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository? = null,
    private val clientTemplateFixWriter:
        ru.liferych.bms.data.diagnostics.ClientTemplateFixWriter? = null,
    private val factorySerialReader:
        ru.liferych.bms.data.identity.FactorySerialReader? = null,
) : ViewModel() {
    val batteryState: StateFlow<BatteryState> = repository.batteryState
    val connectionState: StateFlow<BmsConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<BmsDevice>> = repository.discoveredDevices

    private val _selectedBatteryId = MutableStateFlow<String?>(null)
    val selectedBatteryId: StateFlow<String?> = _selectedBatteryId.asStateFlow()

    private val _savedBatteries = MutableStateFlow(savedBatteriesStore.load())
    val savedBatteries: StateFlow<List<SavedBattery>> = _savedBatteries.asStateFlow()

    private val _presenceScanCompleted = MutableStateFlow(false)
    /** Re-evaluates GATT freshness window without inventing a new timeout policy. */
    private val _presenceTick = MutableStateFlow(0L)
    /** True while Compose «Мои батареи» is visible — controls passive presence scan. */
    private var myBatteriesVisible: Boolean = false
    private val _pendingOpenDashboard = MutableStateFlow(false)
    private val _addBatteryFlow = MutableStateFlow(false)
    /** BLE name remembered until add-flow gets Daly telemetry (do not persist earlier). */
    private var pendingAddBluetoothName: String = ""
    private var addFlowTelemetryJob: Job? = null
    private val _pendingReturnToMyBatteries = MutableStateFlow(false)

    val pendingOpenDashboard: StateFlow<Boolean> = _pendingOpenDashboard.asStateFlow()
    val pendingReturnToMyBatteries: StateFlow<Boolean> = _pendingReturnToMyBatteries.asStateFlow()

    /**
     * Guest tapped «ДОБАВИТЬ БАТАРЕЮ» — after successful login/register open DeviceScan.
     * Cleared on cancel / leave Profile / logout.
     */
    private val _pendingAddBatteryAfterAuth = MutableStateFlow(false)

    /** One-shot: Profile should open Login form (not Guest hub). */
    private val _pendingOpenProfileLogin = MutableStateFlow(false)
    val pendingOpenProfileLogin: StateFlow<Boolean> = _pendingOpenProfileLogin.asStateFlow()

    /** One-shot: navigate to DeviceScan after auth success with pending add intent. */
    private val _pendingNavigateDeviceScan = MutableStateFlow(false)
    val pendingNavigateDeviceScan: StateFlow<Boolean> = _pendingNavigateDeviceScan.asStateFlow()

    /** Auth state from [AuthRepository] (Guest / Authorized / Loading). */
    val authState: StateFlow<AuthState> = authRepository.authState

    private val _profileFeedback = MutableStateFlow(ProfileAuthFeedback())
    val profileFeedback: StateFlow<ProfileAuthFeedback> = _profileFeedback.asStateFlow()

    private val _profileAvatarUi = MutableStateFlow(ProfileAvatarUi())
    /** Avatar upload / preview status (no Bitmap). */
    val profileAvatarUi: StateFlow<ProfileAvatarUi> = _profileAvatarUi.asStateFlow()

    private var avatarUploadJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var configFixJob: Job? = null
    private var diagnosticsFixing: Boolean = false
    private var diagnosticsFixProgress: String = ""
    private var diagnosticsFixError: String = ""
    /** True while Diagnostics forced a live Modbus config read — quiet refresh must not cancel it. */
    private var diagnosticsLiveReadInFlight: Boolean = false
    /** Address for which a live config read was already attempted this connection. */
    private var liveConfigAttemptedAddress: String? = null
    /** Address for which factory SN Modbus read was attempted this connection. */
    private var factorySerialAttemptedAddress: String? = null
    /** Bumps when identity cache SN is updated so Dashboard re-reads prefs. */
    private val _factorySerialTick = MutableStateFlow(0)

    private val _diagnosticsSnapshot =
        MutableStateFlow<ru.liferych.bms.data.diagnostics.DiagnosticsSnapshot?>(null)
    private val _diagnosticsUi =
        MutableStateFlow<ru.liferych.bms.ui.model.DiagnosticsUiState>(
            ru.liferych.bms.ui.model.DiagnosticsUiState.Loading,
        )
    val diagnosticsUi: StateFlow<ru.liferych.bms.ui.model.DiagnosticsUiState> =
        _diagnosticsUi.asStateFlow()

    /**
     * Dashboard «Батарея в норме» banner — legacy TemplateCheck + active errors.
     */
    val dashboardOverallStatus:
        StateFlow<ru.liferych.bms.ui.model.DashboardOverallStatusUi> = combine(
        batteryState,
        _diagnosticsSnapshot,
    ) { battery, snapshot ->
        ru.liferych.bms.ui.model.buildDashboardOverallStatus(
            activeErrors = battery.errors,
            snapshot = snapshot,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ru.liferych.bms.ui.model.DashboardOverallStatusUi(
            title = "…  Идёт инициализация BMS",
            titleColor = androidx.compose.ui.graphics.Color(0xFF6F7781),
        ),
    )

    /**
     * Profile UI projection — real session data only (no demo values in runtime).
     */
    val profile: StateFlow<ProfileUi> = authState.map { state ->
        when (state) {
            is AuthState.Authorized -> state.profile.toProfileUi(appVersionLabel())
            AuthState.Guest,
            AuthState.Loading,
            -> ProfileUi(
                displayName = "",
                email = "",
                phone = "",
                birthDate = "",
                appVersionLabel = appVersionLabel(),
                isAuthorized = false,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileUi(
            displayName = "",
            email = "",
            phone = "",
            birthDate = "",
            appVersionLabel = appVersionLabel(),
            isAuthorized = false,
        ),
    )

    /** Discovered BLE devices mapped for DeviceScan screen. */
    val scanDevices: StateFlow<List<BatterySummaryUi>> = combine(
        discoveredDevices,
        connectionState,
        batteryState,
    ) { devices, connection, battery ->
        devices.map { device ->
            val connected = connection is BmsConnectionState.Connected &&
                connection.deviceAddress.equals(device.address, ignoreCase = true)
            BatterySummaryUi(
                id = device.address,
                name = device.name?.ifBlank { device.address } ?: device.address,
                subtitle = device.address,
                address = device.address,
                socPercent = if (connected) battery.soc else null,
                voltage = if (connected) battery.voltage else null,
                isOnline = connected,
                connectionLabel = when {
                    connected -> "Подключено"
                    connection is BmsConnectionState.Connecting &&
                        connection.deviceAddress.equals(device.address, true) -> "Подключение…"
                    connection is BmsConnectionState.Scanning -> "Найдено"
                    else -> "Доступно"
                },
                rssi = device.rssi,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * Card presence for saved list — legacy-style Checking / Connected / Disconnected / Error.
     */
    val savedBatteryCardStatus: StateFlow<Map<String, SavedBatteryCardStatus>> = combine(
        savedBatteries,
        connectionState,
        batteryState,
        discoveredDevices,
        combine(_presenceScanCompleted, _presenceTick) { done, tick -> done to tick },
    ) { saved, connection, battery, discovered, scanMeta ->
        val scanDone = scanMeta.first
        saved.associate { item ->
            val key = item.address.uppercase()
            key to resolveCardStatus(item, connection, battery, discovered, scanDone)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    val screenStatus: StateFlow<ScreenUiStatus> = combine(
        connectionState,
        batteryState,
    ) { connection, battery ->
        val hasTelemetry = battery.soc != null || battery.voltage != null
        when (connection) {
            is BmsConnectionState.Disconnected -> {
                if (hasTelemetry) ScreenUiStatus.Disconnected else ScreenUiStatus.Empty
            }
            else -> connection.toScreenUiStatus(hasTelemetry)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScreenUiStatus.Loading,
    )

    /**
     * Dashboard «Имя устройства» — display-only.
     *
     * Priority: user alias → sanitized BLE name → `--` (never raw AD garbage / MAC).
     * Raw names used for connect/storage are unchanged.
     */
    val selectedDeviceName: StateFlow<String> = combine(
        selectedBatteryId,
        savedBatteries,
        discoveredDevices,
    ) { id, saved, devices ->
        if (id.isNullOrBlank()) return@combine "--"
        val savedItem = saved.firstOrNull { it.address.equals(id, true) }
        if (savedItem != null) {
            return@combine deviceUiDisplayName(
                customName = savedItem.customName,
                bluetoothName = savedItem.bluetoothName,
                fallback = "--",
            )
        }
        val scanName = devices.firstOrNull { it.address.equals(id, true) }?.name
        sanitizeBleDisplayName(scanName).ifBlank { "--" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "--")

    /**
     * Factory serial for Dashboard «Серийный номер».
     *
     * Source: legacy cache `bms_identity` / `sn_<MAC>` filled by Modbus 0xD2
     * registers 0x0057–0x005D (same path as MainActivity). Not Bluetooth name.
     */
    val selectedFactorySerial: StateFlow<String> = combine(
        selectedBatteryId,
        _factorySerialTick,
    ) { id, _ ->
        val raw = bmsIdentityStore.cachedFactorySerial(id)
        val display = bmsIdentityStore.displayFactorySerial(raw)
        display.ifBlank { "--" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "--")

    init {
        authRepository.restoreSession()
        reloadSavedBatteries()

        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (connectionState.value is BmsConnectionState.Connected) {
                    _presenceTick.value = System.currentTimeMillis()
                }
            }
        }

        viewModelScope.launch {
            var wasScanning = false
            var previous: BmsConnectionState = connectionState.value
            connectionState.collect { state ->
                when (state) {
                    is BmsConnectionState.Scanning -> wasScanning = true
                    else -> {
                        if (wasScanning) {
                            _presenceScanCompleted.value = true
                            wasScanning = false
                        }
                    }
                }
                // After GATT drop while home is visible: one continuous presence scan
                // so ads can flip Offline → online without tap / without periodic restart.
                val leftLive = (previous is BmsConnectionState.Connected ||
                    previous is BmsConnectionState.Connecting) &&
                    state is BmsConnectionState.Disconnected
                previous = state
                if (leftLive && myBatteriesVisible && _savedBatteries.value.isNotEmpty()) {
                    ensurePassivePresenceScan(clearResults = false)
                }
            }
        }
        // Add-flow: GATT Connected is not enough (non-Daly UART may connect).
        // Persist + return to «Мои батареи» only after first Daly voltage/SOC sample.
        viewModelScope.launch {
            combine(connectionState, batteryState, _addBatteryFlow) { c, b, add ->
                Triple(c, b, add)
            }.collect { (c, b, add) ->
                if (!add) return@collect
                // Guest must never persist a newly discovered BMS (legacy auth gate).
                if (authRepository.authState.value !is AuthState.Authorized) {
                    cancelAddFlow(persist = false)
                    return@collect
                }
                val connected = c as? BmsConnectionState.Connected ?: return@collect
                val selected = _selectedBatteryId.value ?: return@collect
                if (!connected.deviceAddress.equals(selected, true)) return@collect
                if (b.voltage == null && b.soc == null) return@collect
                val name = pendingAddBluetoothName.ifBlank { connected.deviceAddress }
                savedBatteriesStore.upsert(
                    address = connected.deviceAddress,
                    bluetoothName = name,
                    soc = b.soc,
                    capacityAh = b.fullCapacityAh,
                )
                reloadSavedBatteries()
                pendingAddBluetoothName = ""
                addFlowTelemetryJob?.cancel()
                addFlowTelemetryJob = null
                _addBatteryFlow.value = false
                _pendingReturnToMyBatteries.value = true
            }
        }
        viewModelScope.launch {
            connectionState.collect { state ->
                if (!_addBatteryFlow.value) return@collect
                when (state) {
                    is BmsConnectionState.Connected -> {
                        if (state.deviceAddress.equals(_selectedBatteryId.value, true)) {
                            watchAddFlowTelemetry(state.deviceAddress)
                        }
                    }
                    is BmsConnectionState.Error -> {
                        cancelAddFlow(persist = false)
                    }
                    is BmsConnectionState.Disconnected,
                    is BmsConnectionState.Scanning,
                    -> Unit
                    is BmsConnectionState.Connecting -> Unit
                    else -> Unit
                }
            }
        }
        // Persist capacity/soc snapshots while connected to a known/add-flow battery.
        viewModelScope.launch {
            combine(connectionState, batteryState) { c, b -> c to b }.collect { (c, b) ->
                val address = (c as? BmsConnectionState.Connected)?.deviceAddress ?: return@collect
                if (b.soc == null && b.fullCapacityAh == null) return@collect
                val known = _savedBatteries.value.any { it.address.equals(address, true) }
                if (!known) return@collect
                val name = discoveredDevices.value
                    .firstOrNull { it.address.equals(address, true) }
                    ?.name
                    .orEmpty()
                    .ifBlank {
                        _savedBatteries.value
                            .firstOrNull { it.address.equals(address, true) }
                            ?.bluetoothName
                            .orEmpty()
                    }
                savedBatteriesStore.upsert(
                    address = address,
                    bluetoothName = name,
                    soc = b.soc,
                    capacityAh = b.fullCapacityAh,
                )
                reloadSavedBatteries()
            }
        }
        // Quiet template-check refresh for Dashboard overall status banner.
        // Does not cancel an in-flight Diagnostics live Modbus read.
        viewModelScope.launch {
            combine(connectionState, batteryState, selectedBatteryId) { c, b, id ->
                Triple(c, b, id)
            }.collect { (c, b, id) ->
                if (c !is BmsConnectionState.Connected) {
                    liveConfigAttemptedAddress = null
                    factorySerialAttemptedAddress = null
                    return@collect
                }
                if (
                    !id.isNullOrBlank() &&
                    (b.voltage != null || b.soc != null)
                ) {
                    refreshDiagnosticsQuiet()
                }
            }
        }
    }

    private fun resolveCardStatus(
        battery: SavedBattery,
        connection: BmsConnectionState,
        telemetry: BatteryState,
        discovered: List<BmsDevice>,
        scanDone: Boolean,
    ): SavedBatteryCardStatus {
        return SavedBatteryPresence.resolve(
            address = battery.address,
            connection = connection,
            telemetry = telemetry,
            discovered = discovered,
            scanDone = scanDone,
            selectedAddress = _selectedBatteryId.value,
        )
    }

    fun reloadSavedBatteries() {
        _savedBatteries.value = savedBatteriesStore.load()
    }

    /**
     * Passive «Мои батареи»: start one continuous passive BLE presence scan when idle.
     * No periodic restart / no GATT.
     */
    fun onMyBatteriesAppear() {
        myBatteriesVisible = true
        reloadSavedBatteries()
        if (_savedBatteries.value.isEmpty()) {
            return
        }
        ensurePassivePresenceScan(clearResults = true)
    }

    /**
     * Leave «Мои батареи»: stop passive presence scan (never during connect).
     */
    fun onMyBatteriesDisappear() {
        myBatteriesVisible = false
        val state = connectionState.value
        if (state is BmsConnectionState.Connected ||
            state is BmsConnectionState.Connecting
        ) {
            return
        }
        repository.stopScan()
    }

    /**
     * Ensures a single continuous ads scan while home is idle.
     * scanDone=true so Scanning does not flicker «Проверяем…».
     */
    private fun ensurePassivePresenceScan(clearResults: Boolean) {
        val state = connectionState.value
        if (state is BmsConnectionState.Connected ||
            state is BmsConnectionState.Connecting
        ) {
            _presenceScanCompleted.value = true
            return
        }
        _presenceScanCompleted.value = true
        if (state is BmsConnectionState.Scanning) {
            return
        }
        repository.startPresenceScan(clearResults = clearResults)
    }

    /**
     * True when Journal may show live BMS faults (GATT Connected).
     */
    fun hasLiveBmsConnection(): Boolean {
        return connectionState.value is BmsConnectionState.Connected
    }

    fun selectBattery(id: String) {
        _selectedBatteryId.value = id
    }

    fun clearSelectedBattery() {
        _selectedBatteryId.value = null
        _pendingOpenDashboard.value = false
    }

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connect(address: String) {
        viewModelScope.launch {
            _selectedBatteryId.value = address
            repository.connect(address)
        }
    }

    /**
     * Tap saved card: always attempt ONE connect (even if card shows offline).
     * Dashboard opens only after live telemetry (see NavHost), not from stale cache.
     */
    fun openSavedBattery(battery: SavedBattery) {
        val current = connectionState.value
        if (current is BmsConnectionState.Connecting &&
            current.deviceAddress.equals(battery.address, true)
        ) {
            _selectedBatteryId.value = battery.address
            _addBatteryFlow.value = false
            _pendingOpenDashboard.value = true
            return
        }
        if (current is BmsConnectionState.Connected &&
            current.deviceAddress.equals(battery.address, true)
        ) {
            _selectedBatteryId.value = battery.address
            _addBatteryFlow.value = false
            _pendingOpenDashboard.value = true
            return
        }
        repository.stopScan()
        _selectedBatteryId.value = battery.address
        _addBatteryFlow.value = false
        _pendingOpenDashboard.value = true
        viewModelScope.launch {
            repository.connect(battery.address, battery.bluetoothName)
        }
    }

    fun consumeOpenDashboard() {
        _pendingOpenDashboard.value = false
    }

    fun consumeReturnToMyBatteries() {
        _pendingReturnToMyBatteries.value = false
    }

    /**
     * Device scan selection while adding: connect first; persist only after
     * Daly telemetry (voltage/SOC) so non-Daly GATT success does not pollute the list.
     * Guest (not Authorized) cannot start or complete add-battery.
     */
    fun addDiscoveredBattery(address: String, name: String) {
        if (authRepository.authState.value !is AuthState.Authorized) {
            cancelAddFlow(persist = false)
            return
        }
        _addBatteryFlow.value = true
        _selectedBatteryId.value = address
        _pendingOpenDashboard.value = false
        pendingAddBluetoothName = name.ifBlank { address }
        viewModelScope.launch {
            repository.connect(address, name.ifBlank { address })
        }
    }

    /**
     * Aborts in-progress add-battery flow (Guest logout / navigation guard).
     * Does not remove already saved batteries.
     */
    fun abortAddBatteryFlow() {
        cancelAddFlow(persist = false)
    }

    /**
     * Guest wants to add a battery: remember intent and open Profile Login.
     */
    fun beginAddBatteryAuthGate() {
        _pendingAddBatteryAfterAuth.value = true
        _pendingOpenProfileLogin.value = true
    }

    /**
     * Clears pending AddBattery-after-auth (Back, cancel Login, leave Profile, other tab).
     */
    fun clearPendingAddBatteryAfterAuth() {
        _pendingAddBatteryAfterAuth.value = false
        _pendingOpenProfileLogin.value = false
    }

    /**
     * Consumes one-shot open-Login flag for ProfileScreen.
     */
    fun consumeOpenProfileLogin() {
        _pendingOpenProfileLogin.value = false
    }

    /**
     * Consumes one-shot navigate-to-DeviceScan after auth.
     */
    fun consumeNavigateDeviceScan() {
        _pendingNavigateDeviceScan.value = false
    }

    /**
     * If Guest started AddBattery via auth gate, continue to DeviceScan after success.
     */
    private fun continuePendingAddBatteryAfterAuth() {
        if (!_pendingAddBatteryAfterAuth.value) return
        _pendingAddBatteryAfterAuth.value = false
        _pendingOpenProfileLogin.value = false
        _pendingNavigateDeviceScan.value = true
    }

    /**
     * Arms a timeout while waiting for first Daly sample after GATT Connected.
     *
     * @param address expected MAC
     */
    private fun watchAddFlowTelemetry(address: String) {
        addFlowTelemetryJob?.cancel()
        addFlowTelemetryJob = viewModelScope.launch {
            delay(ADD_FLOW_TELEMETRY_TIMEOUT_MS)
            if (!_addBatteryFlow.value) return@launch
            if (!_selectedBatteryId.value.equals(address, true)) return@launch
            // No Daly frames — drop session and stay on search.
            cancelAddFlow(persist = false)
            repository.disconnect()
        }
    }

    /**
     * Clears add-flow bookkeeping without inventing a second error channel.
     *
     * @param persist unused; reserved for future explicit save rollback
     */
    private fun cancelAddFlow(persist: Boolean) {
        pendingAddBluetoothName = ""
        addFlowTelemetryJob?.cancel()
        addFlowTelemetryJob = null
        _addBatteryFlow.value = false
    }

    /**
     * Called once at cold start: reloads local saved batteries.
     * No artificial delay — returns as soon as storage is read.
     */
    fun prepareStartup() {
        reloadSavedBatteries()
    }

    fun renameSavedBattery(address: String, customName: String) {
        savedBatteriesStore.rename(address, customName)
        reloadSavedBatteries()
    }

    fun deleteSavedBattery(address: String) {
        val wasSelected = _selectedBatteryId.value.equals(address, true)
        savedBatteriesStore.remove(address)
        reloadSavedBatteries()
        if (wasSelected) {
            viewModelScope.launch { repository.disconnect() }
            clearSelectedBattery()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    /**
     * Clears profile form feedback (status / suggest flags).
     */
    fun clearProfileFeedback() {
        _profileFeedback.value = ProfileAuthFeedback()
    }

    /**
     * Clears avatar status message / upload flags (keeps displayed local path from auth).
     */
    fun clearProfileAvatarMessage() {
        _profileAvatarUi.value = _profileAvatarUi.value.copy(message = "")
    }

    /**
     * Encodes picked/captured image and uploads via legacy avatar endpoint.
     *
     * @param uri content URI from TakePicture / PickVisualMedia
     *
     * Side effects: updates [profileAvatarUi] + [authState] via repository.
     * Security: does not log image bytes.
     */
    fun updateAvatarFromUri(uri: Uri) {
        if (_profileAvatarUi.value.isUploading) return
        val encoder = avatarEncoder
        if (encoder == null) {
            _profileAvatarUi.value = ProfileAvatarUi(
                message = "Смена фото недоступна",
            )
            return
        }
        if (authRepository.authState.value !is AuthState.Authorized) {
            _profileAvatarUi.value = ProfileAvatarUi(
                message = "Сначала войдите в профиль",
            )
            return
        }
        avatarUploadJob?.cancel()
        avatarUploadJob = viewModelScope.launch {
            _profileAvatarUi.value = ProfileAvatarUi(
                localPreviewPath = _profileAvatarUi.value.localPreviewPath,
                isUploading = true,
                message = "Загрузка фото…",
            )
            val jpeg = withContext(Dispatchers.IO) { encoder.encodeJpeg(uri) }
            if (jpeg == null || jpeg.isEmpty()) {
                _profileAvatarUi.value = ProfileAvatarUi(
                    message = "Не удалось прочитать изображение",
                )
                return@launch
            }
            when (val result = authRepository.updateAvatar(jpeg)) {
                is AuthResult.Success -> {
                    _profileAvatarUi.value = ProfileAvatarUi(
                        localPreviewPath = result.profile.avatarLocalPath,
                        isUploading = false,
                        message = "Фото профиля сохранено",
                    )
                }
                AuthResult.NetworkError -> {
                    _profileAvatarUi.value = ProfileAvatarUi(
                        message = "Не удалось загрузить фото. Попробуйте еще раз.",
                    )
                }
                is AuthResult.ServerError -> {
                    _profileAvatarUi.value = ProfileAvatarUi(
                        message = result.message.ifBlank {
                            "Не удалось загрузить фото. Попробуйте еще раз."
                        },
                    )
                }
                is AuthResult.ValidationError -> {
                    _profileAvatarUi.value = ProfileAvatarUi(message = result.message)
                }
                else -> {
                    _profileAvatarUi.value = ProfileAvatarUi(
                        message = "Не удалось загрузить фото. Попробуйте еще раз.",
                    )
                }
            }
        }
    }

    /**
     * Login by national 10 digits (UI) → E.164 API.
     *
     * @param phoneNational national digits / mask from Profile login form
     */
    fun login(phoneNational: String) {
        if (_profileFeedback.value.isSubmitting) return
        val e164 = RuPhone.toE164(phoneNational)
        if (RuPhone.extractNationalDigits(phoneNational).length != 10 || e164.isBlank()) {
            _profileFeedback.value = ProfileAuthFeedback(message = "Укажите 10 цифр номера")
            return
        }
        viewModelScope.launch {
            _profileFeedback.value = ProfileAuthFeedback(message = "Вход…", isSubmitting = true)
            // Legacy disconnects before login while swapping battery list.
            repository.disconnect()
            clearSelectedBattery()
            when (val result = authRepository.login(e164)) {
                is AuthResult.Success -> {
                    reloadSavedBatteries()
                    _profileFeedback.value = ProfileAuthFeedback()
                    continuePendingAddBatteryAfterAuth()
                }
                AuthResult.UserNotFound -> {
                    _profileFeedback.value = ProfileAuthFeedback(
                        message = "Пользователь с таким номером не найден.\nЗарегистрируйтесь, чтобы создать профиль.",
                        suggestRegister = true,
                    )
                }
                AuthResult.NetworkError -> {
                    _profileFeedback.value = ProfileAuthFeedback(
                        message = "Не удалось связаться с сервером. Проверьте интернет и повторите.",
                    )
                }
                is AuthResult.ServerError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                is AuthResult.ValidationError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                AuthResult.PhoneAlreadyExists -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = "Ошибка входа")
                }
            }
        }
    }

    /**
     * Register with legacy fields: ФИО + phone (email/birth from blank prefs defaults).
     *
     * @param fullName ФИО
     * @param phoneNational national phone from form
     */
    fun register(fullName: String, phoneNational: String) {
        if (_profileFeedback.value.isSubmitting) return
        val name = fullName.trim()
        if (name.isBlank()) {
            _profileFeedback.value = ProfileAuthFeedback(message = "Укажите ФИО")
            return
        }
        val e164 = RuPhone.toE164(phoneNational)
        if (RuPhone.extractNationalDigits(phoneNational).length != 10 || e164.isBlank()) {
            _profileFeedback.value = ProfileAuthFeedback(message = "Укажите 10 цифр номера")
            return
        }
        viewModelScope.launch {
            _profileFeedback.value = ProfileAuthFeedback(message = "Регистрация…", isSubmitting = true)
            repository.disconnect()
            clearSelectedBattery()
            when (val result = authRepository.register(fullName = name, phoneE164 = e164)) {
                is AuthResult.Success -> {
                    reloadSavedBatteries()
                    _profileFeedback.value = ProfileAuthFeedback()
                    continuePendingAddBatteryAfterAuth()
                }
                AuthResult.PhoneAlreadyExists -> {
                    _profileFeedback.value = ProfileAuthFeedback(
                        message = "Пользователь с таким номером уже зарегистрирован.\nВыполните вход.",
                        suggestLogin = true,
                    )
                }
                AuthResult.NetworkError -> {
                    _profileFeedback.value = ProfileAuthFeedback(
                        message = "Не удалось подключиться к серверу. Проверьте интернет и повторите.",
                    )
                }
                is AuthResult.ServerError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                is AuthResult.ValidationError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                AuthResult.UserNotFound -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = "Ошибка регистрации")
                }
            }
        }
    }

    /**
     * Saves profile fields via existing /profile API.
     *
     * @param fullName ФИО
     * @param phoneNational national digits from form
     * @param email email
     * @param birthDate birth string
     */
    fun saveProfile(
        fullName: String,
        phoneNational: String,
        email: String,
        birthDate: String,
    ) {
        if (_profileFeedback.value.isSubmitting) return
        viewModelScope.launch {
            _profileFeedback.value = ProfileAuthFeedback(message = "Сохранение…", isSubmitting = true)
            val e164 = RuPhone.toE164(phoneNational)
            when (
                val result = authRepository.updateProfile(
                    fullName = fullName,
                    phoneE164 = e164,
                    email = email,
                    birthDate = birthDate,
                )
            ) {
                is AuthResult.Success -> {
                    reloadSavedBatteries()
                    _profileFeedback.value = ProfileAuthFeedback(message = "Профиль сохранён")
                }
                is AuthResult.ValidationError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                AuthResult.NetworkError -> {
                    _profileFeedback.value = ProfileAuthFeedback(
                        message = "Профиль сохранён локально, сервер недоступен",
                    )
                }
                is AuthResult.ServerError -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = result.message)
                }
                else -> {
                    _profileFeedback.value = ProfileAuthFeedback(message = "Не удалось сохранить профиль")
                }
            }
        }
    }

    /**
     * Local logout (legacy): clears session + local batteries; disconnects BLE.
     * Does not call server delete.
     */
    fun logout() {
        viewModelScope.launch {
            cancelAddFlow(persist = false)
            clearPendingAddBatteryAfterAuth()
            repository.disconnect()
            clearSelectedBattery()
            authRepository.logout()
            reloadSavedBatteries()
            _profileFeedback.value = ProfileAuthFeedback()
        }
    }

    /**
     * Opens Diagnostics: live BMS required; loads template check (legacy semantics).
     */
    fun openDiagnostics() {
        diagnosticsFixError = ""
        diagnosticsFixProgress = ""
        diagnosticsFixing = false
        val connected = connectionState.value is BmsConnectionState.Connected
        if (!connected) {
            _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Offline
            return
        }
        // Never show previous check as current while a fresh read is in progress.
        _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Loading
        refreshDiagnostics(forceUi = true)
    }

    /**
     * Legacy «ИСПРАВИТЬ»: write only mismatched writable params, then reread/verify.
     */
    fun applyConfigFix() {
        if (diagnosticsFixing) return
        if (connectionState.value !is BmsConnectionState.Connected) {
            diagnosticsFixError = "Нет связи с BMS"
            publishDiagnosticsUiFromSnapshot()
            return
        }
        val fixer = clientTemplateFixWriter ?: run {
            diagnosticsFixError = "Запись конфигурации недоступна"
            publishDiagnosticsUiFromSnapshot()
            return
        }
        val address = _selectedBatteryId.value ?: return
        diagnosticsFixing = true
        diagnosticsFixError = ""
        diagnosticsFixProgress = "Исправляем конфигурацию…"
        publishDiagnosticsUiFromSnapshot()
        configFixJob?.cancel()
        configFixJob = viewModelScope.launch {
            val battery = batteryState.value
            val savedName = _savedBatteries.value
                .firstOrNull { it.address.equals(address, true) }
                ?.bluetoothName
                .orEmpty()
            val uid = ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository.bmsUid(
                address = address,
                bluetoothName = savedName,
            )
            val result = withContext(Dispatchers.IO) {
                fixer.applyFix(
                    bmsUid = uid,
                    battery = battery,
                    bluetoothName = savedName,
                    onProgress = { text ->
                        mainHandlerPost {
                            diagnosticsFixProgress = text
                            publishDiagnosticsUiFromSnapshot()
                        }
                    },
                )
            }
            diagnosticsFixing = false
            diagnosticsFixProgress = ""
            when (result) {
                is ru.liferych.bms.data.diagnostics.ClientFixResult.Success -> {
                    _diagnosticsSnapshot.value = result.snapshot
                    diagnosticsFixError = ""
                    _diagnosticsUi.value = ru.liferych.bms.ui.model.buildDiagnosticsContent(
                        snapshot = result.snapshot,
                        isFixing = false,
                        fixProgressText = "",
                        fixError = "",
                    )
                }
                is ru.liferych.bms.data.diagnostics.ClientFixResult.Failed -> {
                    diagnosticsFixError = result.message
                    refreshDiagnostics(forceUi = false)
                    publishDiagnosticsUiFromSnapshot()
                }
            }
        }
    }

    private fun mainHandlerPost(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun publishDiagnosticsUiFromSnapshot() {
        val snap = _diagnosticsSnapshot.value
        if (snap != null) {
            _diagnosticsUi.value = ru.liferych.bms.ui.model.buildDiagnosticsContent(
                snapshot = snap,
                isFixing = diagnosticsFixing,
                fixProgressText = diagnosticsFixProgress,
                fixError = diagnosticsFixError,
            )
        } else if (diagnosticsFixing) {
            _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Content(
                checkedAtText = null,
                overallTitle = "Исправляем конфигурацию…",
                overallStatus = null,
                overallMarkColor = androidx.compose.ui.graphics.Color(0xFF6F7781),
                categories = emptyList(),
                isFixing = true,
                fixProgressText = diagnosticsFixProgress,
                fixError = diagnosticsFixError,
            )
        }
    }

    /**
     * Once per connected MAC: Modbus SN Code → identity cache → Dashboard.
     * Retries later when config I/O is busy (does not mark attempt as final).
     */
    private suspend fun refreshFactorySerialAfterDiagnostics(bleAddress: String) {
        val reader = factorySerialReader ?: return
        if (factorySerialAttemptedAddress.equals(bleAddress, true)) return
        if (!bmsIdentityStore.cachedFactorySerial(bleAddress).isNullOrBlank()) {
            factorySerialAttemptedAddress = bleAddress
            _factorySerialTick.value = _factorySerialTick.value + 1
            return
        }
        val result = withContext(Dispatchers.IO) {
            reader.refreshIfNeeded(bleAddress, force = false)
        }
        when (result) {
            is ru.liferych.bms.data.identity.FactorySerialRefresh.Done -> {
                factorySerialAttemptedAddress = bleAddress
                if (result.display.isNotBlank()) {
                    _factorySerialTick.value = _factorySerialTick.value + 1
                }
            }
            ru.liferych.bms.data.identity.FactorySerialRefresh.Failed -> {
                factorySerialAttemptedAddress = bleAddress
            }
            ru.liferych.bms.data.identity.FactorySerialRefresh.Busy -> {
                // Leave attempted unset so a later quiet refresh can retry.
            }
        }
    }

    /**
     * Refreshes template diagnostics without forcing Loading chrome (Dashboard banner).
     * Skips when a job is already running so telemetry polls do not cancel Modbus reads.
     */
    private fun refreshDiagnosticsQuiet() {
        if (diagnosticsJob?.isActive == true || diagnosticsLiveReadInFlight) return
        refreshDiagnostics(forceUi = false)
    }

    /**
     * Runs config-template check via [configDiagnosticsRepository].
     *
     * When config registers are not cached yet (or Diagnostics opened), performs one
     * live Modbus read via [clientTemplateFixWriter] so UI leaves "checking".
     *
     * @param forceUi when true, show Loading until result (Diagnostics screen)
     */
    private fun refreshDiagnostics(forceUi: Boolean) {
        val repo = configDiagnosticsRepository ?: return
        val address = _selectedBatteryId.value ?: return
        val connected = connectionState.value is BmsConnectionState.Connected
        if (!connected) {
            _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Offline
            return
        }
        if (forceUi && !diagnosticsFixing) {
            _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Loading
        }
        if (forceUi) {
            diagnosticsJob?.cancel()
        } else if (diagnosticsJob?.isActive == true) {
            return
        }
        diagnosticsJob = viewModelScope.launch {
            val battery = batteryState.value
            val savedName = _savedBatteries.value
                .firstOrNull { it.address.equals(address, true) }
                ?.bluetoothName
                .orEmpty()
            val uid = ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository.bmsUid(
                address = address,
                bluetoothName = savedName,
            )
            val cachedRegisters = repo.loadRegisters(uid)
            val fixer = clientTemplateFixWriter
            val needLiveRead = fixer != null && (
                forceUi ||
                    (cachedRegisters.isEmpty() && liveConfigAttemptedAddress != address)
                )
            val snapshot = if (needLiveRead && fixer != null) {
                diagnosticsLiveReadInFlight = true
                try {
                    withContext(Dispatchers.IO) {
                        fixer.refreshLiveConfig(
                            bmsUid = uid,
                            battery = battery,
                            bluetoothName = savedName,
                        )
                    }
                } finally {
                    diagnosticsLiveReadInFlight = false
                    liveConfigAttemptedAddress = address
                }
            } else {
                withContext(Dispatchers.IO) {
                    repo.refresh(
                        bmsUid = uid,
                        battery = battery,
                        bluetoothName = savedName,
                    )
                }
            }
            if (connectionState.value !is BmsConnectionState.Connected) {
                _diagnosticsUi.value = ru.liferych.bms.ui.model.DiagnosticsUiState.Offline
                return@launch
            }
            _diagnosticsSnapshot.value = snapshot
            _diagnosticsUi.value = ru.liferych.bms.ui.model.buildDiagnosticsContent(
                snapshot = snapshot,
                isFixing = diagnosticsFixing,
                fixProgressText = diagnosticsFixProgress,
                fixError = diagnosticsFixError,
            )
            refreshFactorySerialAfterDiagnostics(address)
        }
    }

    private fun appVersionLabel(): String {
        return "v${BuildConfig.VERSION_NAME}"
    }

    private fun UserProfile.toProfileUi(version: String): ProfileUi {
        return ProfileUi(
            displayName = fullName,
            email = email,
            phone = RuPhone.formatDisplay(phoneE164),
            birthDate = birthDate,
            appVersionLabel = version,
            isAuthorized = true,
        )
    }

    class Factory(
        private val repository: BmsRepository,
        private val savedBatteriesStore: SavedBatteriesStore,
        private val bmsIdentityStore: BmsIdentityStore,
        private val authRepository: AuthRepository,
        private val avatarEncoder: ProfileAvatarEncoder? = null,
        private val configDiagnosticsRepository:
            ru.liferych.bms.data.diagnostics.ConfigDiagnosticsRepository? = null,
        private val clientTemplateFixWriter:
            ru.liferych.bms.data.diagnostics.ClientTemplateFixWriter? = null,
        private val factorySerialReader:
            ru.liferych.bms.data.identity.FactorySerialReader? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FrontendViewModel::class.java)) {
                return FrontendViewModel(
                    repository,
                    savedBatteriesStore,
                    bmsIdentityStore,
                    authRepository,
                    avatarEncoder,
                    configDiagnosticsRepository,
                    clientTemplateFixWriter,
                    factorySerialReader,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    companion object {
        private const val ADD_FLOW_TELEMETRY_TIMEOUT_MS = 12_000L

        const val DEMO_SELECTED_ID = "battery-demo-1"

        fun demoBatteries(): List<BatterySummaryUi> = listOf(
            BatterySummaryUi(
                id = DEMO_SELECTED_ID,
                name = "DALY-BMS-16S",
                subtitle = "DALY-BMS-16S",
                address = "AA:BB:CC:DD:EE:01",
                socPercent = 78.0,
                voltage = 51.8,
                isOnline = true,
                connectionLabel = "Подключено",
                serialNumber = "224LG151200441",
                bmsVersion = "JHB-R24TK-V2.1",
            ),
        )
    }
}
