package ru.liferych.bms.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.repository.BmsRepository
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.model.ProfileUi
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.model.toScreenUiStatus

/**
 * ViewModel for the new Compose client frontend.
 * Composables observe this VM — never touch [BmsRepository] directly.
 */
class FrontendViewModel(
    private val repository: BmsRepository,
) : ViewModel() {
    val batteryState: StateFlow<BatteryState> = repository.batteryState
    val connectionState: StateFlow<BmsConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<BmsDevice>> = repository.discoveredDevices

    private val _selectedBatteryId = MutableStateFlow<String?>(null)
    val selectedBatteryId: StateFlow<String?> = _selectedBatteryId.asStateFlow()

    private val _profile = MutableStateFlow(
        ProfileUi(
            displayName = "Демо пользователь",
            email = "demo@liferych.ru",
            phone = "+7 (900) 000-00-00",
            birthDate = "",
            appVersionLabel = "Frontend",
            isAuthorized = true,
        ),
    )
    val profile: StateFlow<ProfileUi> = _profile.asStateFlow()

    val batteries: StateFlow<List<BatterySummaryUi>> = combine(
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
                serialNumber = if (connected) {
                    battery.factorySerial?.takeIf { it.isNotBlank() } ?: "--"
                } else {
                    "--"
                },
                bmsVersion = if (connected) {
                    battery.bmsHwVersion?.takeIf { it.isNotBlank() } ?: "--"
                } else {
                    "--"
                },
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val screenStatus: StateFlow<ScreenUiStatus> = combine(
        connectionState,
        batteryState,
    ) { connection, battery ->
        val hasTelemetry = battery.soc != null || battery.voltage != null
        when (connection) {
            is BmsConnectionState.Disconnected -> {
                // Do not show fake/stale telemetry as Connected.
                if (hasTelemetry) ScreenUiStatus.Disconnected else ScreenUiStatus.Empty
            }
            else -> connection.toScreenUiStatus(hasTelemetry)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScreenUiStatus.Loading,
    )

    val selectedDeviceName: StateFlow<String> = combine(
        selectedBatteryId,
        discoveredDevices,
        batteryState,
    ) { id, devices, _ ->
        devices.firstOrNull { it.address == id }?.name?.ifBlank { null }
            ?: id
            ?: "Батарея"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Батарея")

    fun selectBattery(id: String) {
        _selectedBatteryId.value = id
    }

    fun clearSelectedBattery() {
        _selectedBatteryId.value = null
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

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    class Factory(
        private val repository: BmsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FrontendViewModel::class.java)) {
                return FrontendViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    companion object {
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
