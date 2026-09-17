package ru.liferych.bms.domain.auth

/**
 * Domain user profile from local session / server `user` object.
 * Fields match legacy prefs + API (`name`, `phone`, `email`, `birth`, `avatar_url`).
 *
 * No secrets. Phone is stored as E.164 (+7…).
 */
data class UserProfile(
    val fullName: String,
    val phoneE164: String,
    val email: String = "",
    val birthDate: String = "",
    val avatarUrl: String = "",
    val avatarLocalPath: String = "",
)

/**
 * Application-level auth state for Compose Profile.
 * Session is local SharedPreferences (`user_profile`), not a JWT.
 */
sealed interface AuthState {
    /** Reading prefs / restoring session at startup. */
    data object Loading : AuthState

    /** No active local session. */
    data object Guest : AuthState

    /** Local session active with profile fields. */
    data class Authorized(val profile: UserProfile) : AuthState
}

/**
 * Result of login / register / profile update network call.
 * Does not contain passwords or API keys.
 */
sealed interface AuthResult {
    data class Success(val profile: UserProfile) : AuthResult

    /** Login: user not found (HTTP 404 / error=user_not_found). */
    data object UserNotFound : AuthResult

    /** Register: phone already exists (HTTP 409 / error=phone_already_exists). */
    data object PhoneAlreadyExists : AuthResult

    /** Transport failure or empty body. */
    data object NetworkError : AuthResult

    /**
     * Server rejected request (validation, API key, 5xx, etc.).
     *
     * @param message safe user-facing text (no secrets)
     * @param httpCode HTTP status when known; 0 if unknown
     * @param errorCode server `error` field when present
     */
    data class ServerError(
        val message: String,
        val httpCode: Int = 0,
        val errorCode: String = "",
    ) : AuthResult

    /** Client-side validation failed before network. */
    data class ValidationError(val message: String) : AuthResult
}
