package ru.liferych.bms.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liferych.bms.telemetry.TelemetryHistoryChartState
import ru.liferych.bms.telemetry.TelemetryHistoryPeriod
import ru.liferych.bms.telemetry.TelemetryHistoryRepository

/**
 * Loads local Voltage/Current history for the Compose charts screen.
 */
class ChartsViewModel(
    private val historyRepository: TelemetryHistoryRepository,
) : ViewModel() {
    private val _period = MutableStateFlow(TelemetryHistoryPeriod.H24)
    val period: StateFlow<TelemetryHistoryPeriod> = _period.asStateFlow()

    private val _chartState = MutableStateFlow(
        TelemetryHistoryChartState(
            period = TelemetryHistoryPeriod.H24,
            fromMs = 0L,
            toMs = 0L,
            points = emptyList(),
            voltageMin = null,
            voltageMax = null,
            voltageAvg = null,
            currentMin = null,
            currentAvg = null,
            currentMax = null,
            rawCount = 0,
            loading = true,
            empty = true,
        ),
    )
    val chartState: StateFlow<TelemetryHistoryChartState> = _chartState.asStateFlow()

    private var activeBmsUid: String = ""
    private var loadJob: Job? = null

    /**
     * Binds the chart to [bmsUid] and reloads the current period.
     */
    fun bindBattery(bmsUid: String) {
        if (bmsUid == activeBmsUid && !_chartState.value.loading) {
            // Still refresh so newly recorded points appear when reopening.
        }
        activeBmsUid = bmsUid
        reload()
    }

    /**
     * Changes period chips and reloads history.
     */
    fun selectPeriod(period: TelemetryHistoryPeriod) {
        if (_period.value == period) return
        _period.value = period
        reload()
    }

    /**
     * Reloads history for the active battery/period.
     */
    fun reload() {
        val uid = activeBmsUid
        val selected = _period.value
        loadJob?.cancel()
        _chartState.value = _chartState.value.copy(loading = true, period = selected)
        loadJob = viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                historyRepository.loadChartState(uid, selected)
            }
            _chartState.value = state
        }
    }

    class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChartsViewModel::class.java)) {
                return ChartsViewModel(
                    TelemetryHistoryRepository(appContext.applicationContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
