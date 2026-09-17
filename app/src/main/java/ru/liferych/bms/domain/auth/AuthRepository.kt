package ru.liferych.bms.domain.auth

import kotlinx.coroutines.flow.StateFlow
import ru.liferych.bms.data.local.SavedBattery

/**
 * CLIENT auth + profile repository.
 *
 * Uses the same backend contracts as legacy MainActivity:
 * POST /api/v1/users/login|register|profile with app `api_key` + phone identity.
 * Session lives in SharedPreferences `user_profile` (no user JWT).
 */
interface AuthRepository {
    /** Current auth state; never holds secrets. */
    val authState: StateFlow<AuthState>

    /**
     * Restores session from local prefs (no network).
     * Side effect: updates [authState] to Guest or Authorized.
     */
    fun restoreSession()

    /**
     * Login by phone (E.164). Does not create a user.
     *
     * @param phoneE164 +7XXXXXXXXXX
     * @return AuthResult; on Success also replaces local batteries from server
     * @throws nothing — failures returned as AuthResult
     */
    suspend fun login(phoneE164: String): AuthResult

    /**
     * Register new user (phone UNIQUE on server).
     *
     * @param fullName required ФИО
     * @param phoneE164 +7XXXXXXXXXX
     * @param email optional; may be blank
     * @param birthDate optional; may be blank
     * @return AuthResult; Success applies same session as login
     */
    suspend fun register(
        fullName: String,
        phoneE164: String,
        email: String = "",
        birthDate: String = "",
    ): AuthResult

    /**
     * Updates profile on server and local session.
     *
     * @param fullName ФИО
     * @param phoneE164 new phone E.164
     * @param email email
     * @param birthDate birth string as stored by legacy
     * @return AuthResult; ValidationError on blank name / invalid phone
     */
    suspend fun updateProfile(
        fullName: String,
        phoneE164: String,
        email: String,
        birthDate: String,
    ): AuthResult

    /**
     * Uploads profile avatar JPEG (legacy POST /api/v1/users/avatar).
     *
     * Caller must already EXIF-normalize + compress the image.
     * On failure previous local avatar + session fields are preserved.
     *
     * @param jpegBytes JPEG payload (max size enforced by caller)
     * @return AuthResult.Success with updated avatar_url/local path, or error
     *
     * Side effects: writes `filesDir/profile_avatars/avatar_*.jpg`, updates session prefs + authState.
     * Security: does not log image bytes.
     */
    suspend fun updateAvatar(jpegBytes: ByteArray): AuthResult

    /**
     * Local logout only (legacy): clears `user_profile` and local batteries list.
     * Does not call server delete. Does not wipe per-user avatar cache files.
     *
     * Side effects: authState -> Guest; local batteries cleared.
     */
    fun logout()

    /**
     * Batteries returned by last successful login/register (already persisted).
     * Exposed for UI toast / list refresh.
     */
    fun lastSyncedBatteries(): List<SavedBattery>
}
