package ru.liferych.bms.ui.model

import ru.liferych.bms.domain.model.BmsConnectionState

/**
 * High-level screen presentation status for Compose screens.
 * Derived from repository connection + availability of telemetry.
 */
enum class ScreenUiStatus {
    Loading,
    Disconnected,
    Connected,
    Empty,
    Error,
}

/**
 * Client "My Batteries" list item.
 *
 * Note: multi-battery ownership / cloud catalog is not part of [ru.liferych.bms.domain.repository.BmsRepository]
 * (BLE-focused). This is a UI-layer model for frontend development until a user-batteries
 * data source is introduced. Not inventing Daly/BMS protocol fields.
 */
data class BatterySummaryUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val address: String?,
    val socPercent: Double?,
    val voltage: Double?,
    val isOnline: Boolean,
    val connectionLabel: String,
    val serialNumber: String = "--",
    val bmsVersion: String = "--",
    /** BLE RSSI from scan; 0 when unknown / not from discovery. */
    val rssi: Int = 0,
)

/**
 * Active BMS fault/alarm title for CLIENT Journal.
 * Source: [ru.liferych.bms.domain.model.BatteryState.errors] from Daly 0x98.
 * Not an event-history entry — no timestamp / lifecycle fields.
 */
data class ActiveBmsErrorUi(
    val title: String,
)

/**
 * Client profile UI model for Compose Profile screen.
 * Mapped from [ru.liferych.bms.domain.auth.UserProfile] / AuthState.
 * Not an auth token container — no secrets / passwords / API keys.
 */
data class ProfileUi(
    val displayName: String,
    val email: String,
    val phone: String,
    val birthDate: String = "",
    val appVersionLabel: String,
    val isAuthorized: Boolean = false,
)

/**
 * Transient auth form feedback for Profile login/register/save.
 * No secrets.
 */
data class ProfileAuthFeedback(
    val message: String = "",
    val isSubmitting: Boolean = false,
    val suggestRegister: Boolean = false,
    val suggestLogin: Boolean = false,
)

/**
 * Avatar change UI state for Compose Profile (does not hold Bitmap).
 *
 * @property localPreviewPath absolute path for immediate preview after encode
 * @property isUploading true while POST /users/avatar runs
 * @property message status / error text under avatar
 */
data class ProfileAvatarUi(
    val localPreviewPath: String = "",
    val isUploading: Boolean = false,
    val message: String = "",
)

fun BmsConnectionState.toScreenUiStatus(hasTelemetry: Boolean): ScreenUiStatus {
    return when (this) {
        is BmsConnectionState.Scanning,
        is BmsConnectionState.Connecting,
        -> ScreenUiStatus.Loading

        is BmsConnectionState.Disconnected -> {
            if (hasTelemetry) ScreenUiStatus.Disconnected else ScreenUiStatus.Empty
        }

        is BmsConnectionState.Connected -> {
            // First connect: wait for telemetry — Loading, not Empty («Нет данных»).
            if (hasTelemetry) ScreenUiStatus.Connected else ScreenUiStatus.Loading
        }

        is BmsConnectionState.Error -> ScreenUiStatus.Error
    }
}
