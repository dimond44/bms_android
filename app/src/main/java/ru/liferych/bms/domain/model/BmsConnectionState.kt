package ru.liferych.bms.domain.model

/**
 * BLE connection lifecycle for a Daly BMS (UI-independent).
 */
sealed interface BmsConnectionState {
    data object Disconnected : BmsConnectionState

    data object Scanning : BmsConnectionState

    data class Connecting(
        val deviceAddress: String,
    ) : BmsConnectionState

    data class Connected(
        val deviceAddress: String,
    ) : BmsConnectionState

    data class Error(
        val message: String,
    ) : BmsConnectionState
}
