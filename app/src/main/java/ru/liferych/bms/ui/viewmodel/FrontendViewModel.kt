package ru.liferych.bms.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.repository.BmsRepository
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.model.ProfileUi
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.model.toScreenUiStatus

/**
 * ViewModel for the new Compose client frontend.
 * Composables must observe this VM — never touch [BmsRepository] directly.
 *
 * Battery list / profile are UI-layer demo data until product APIs are wired.
 * Active BMS errors for Journal come from [BatteryState.errors] (Daly 0x98).
 */
class FrontendViewModel(
    private val repository: BmsRepository,
) : ViewModel() {
    val batteryState: StateFlow<BatteryState> = repository.batteryState
    val connectionState: StateFlow<BmsConnectionState> = repository.connectionState

    private val _selectedBatteryId = MutableStateFlow<String?>(DEMO_SELECTED_ID)
    val selectedBatteryId: StateFlow<String?> = _selectedBatteryId.asStateFlow()

    private val _batteries = MutableStateFlow(demoBatteries())
    val batteries: StateFlow<List<BatterySummaryUi>> = _batteries.asStateFlow()

    private val _profile = MutableStateFlow(
        ProfileUi(
            displayName = "Демо пользователь",
            email = "demo@liferych.ru",
            phone = "+7 (900) 000-00-00",
            birthDate = "",
            appVersionLabel = "Frontend preview",
            isAuthorized = true,
        ),
    )
    val profile: StateFlow<ProfileUi> = _profile.asStateFlow()

    val screenStatus: StateFlow<ScreenUiStatus> = combine(
        connectionState,
        batteryState,
    ) { connection, battery ->
        val hasTelemetry = battery.soc != null || battery.voltage != null
        connection.toScreenUiStatus(hasTelemetry)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScreenUiStatus.Loading,
    )

    fun selectBattery(id: String) {
        _selectedBatteryId.value = id
    }

    fun clearSelectedBattery() {
        _selectedBatteryId.value = null
    }

    fun connect(address: String) {
        viewModelScope.launch {
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
            BatterySummaryUi(
                id = "battery-demo-2",
                name = "DALY-BMS-Garage",
                subtitle = "DALY-BMS-Garage",
                address = "AA:BB:CC:DD:EE:02",
                socPercent = 42.0,
                voltage = 26.1,
                isOnline = false,
                connectionLabel = "Не в сети",
                serialNumber = "SN-GARAGE-02",
                bmsVersion = "DALY-V1",
            ),
        )
    }
}
