package ru.liferych.bms.ui.model

/**
 * Presence / connection status for a saved battery card on «Мои батареи».
 * Mirrors legacy BatteryPresenceState + connection error for Compose.
 */
enum class SavedBatteryCardStatus {
    /** Presence scan / wake in progress. */
    Checking,

    /** BLE connected with live telemetry, or seen advertising. */
    Connected,

    /** Not advertising / not connected after check. */
    Disconnected,

    /** Connect attempt failed. */
    ConnectionError,
}
