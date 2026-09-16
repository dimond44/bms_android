package ru.liferych.bms.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.liferych.bms.domain.model.BatteryState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.domain.model.BmsDevice
import ru.liferych.bms.domain.repository.BmsRepository

/**
 * Thin presentation wrapper over [BmsRepository].
 * No Daly parsing / GATT details here.
 */
class BmsViewModel(
    private val repository: BmsRepository,
) : ViewModel() {
    val batteryState: StateFlow<BatteryState> = repository.batteryState
    val connectionState: StateFlow<BmsConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<BmsDevice>> = repository.discoveredDevices

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

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    class Factory(
        private val repository: BmsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BmsViewModel::class.java)) {
                return BmsViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
