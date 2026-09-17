package ru.liferych.bms.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.liferych.bms.data.local.SavedBattery
import ru.liferych.bms.domain.auth.AuthRepository
import ru.liferych.bms.domain.auth.AuthResult
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.auth.UserProfile

/**
 * Minimal batteries persistence used by auth login/logout (legacy saveBatteries).
 */
fun interface AuthBatteriesStore {
    /**
     * Replaces the full local batteries list.
     *
     * @param items new list (may be empty)
     */
    fun save(items: List<SavedBattery>)
}

/**
 * Auth + profile repository implementing legacy MainActivity user flow.
 *
 * Session: SharedPreferences `user_profile`.
 * Network: [UserAuthApi] on IO dispatcher.
 * Batteries: on login/register success replaces local list (legacy); logout clears list.
 *
 * Note: legacy 401 means app API-key failure, not user-session expiry.
 * Local session is not a JWT — [invalidateLocalSession] is used only when
 * an explicit invalid-session signal is needed (unit tests / future backend).
 */
class UserAuthRepositoryImpl(
    private val sessionStore: AuthSessionStorage,
    private val api: UserAuthApi,
    private val batteriesStore: AuthBatteriesStore,
    private val avatarFiles: ProfileAvatarPathProvider? = null,
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    @Volatile
    private var lastBatteries: List<SavedBattery> = emptyList()

    override fun restoreSession() {
        val profile = sessionStore.readActiveProfile()
        _authState.value = if (profile != null) {
            // Legacy loadProfileAvatarInto: prefer filesDir/profile_avatars/avatar_<digits>.jpg
            // when prefs avatar_uri is blank but the cache file still exists.
            val hydrated = hydrateLocalAvatarPath(profile)
            if (hydrated.avatarLocalPath != profile.avatarLocalPath) {
                sessionStore.saveAuthorized(hydrated)
            }
            AuthState.Authorized(hydrated)
        } else {
            AuthState.Guest
        }
    }

    /**
     * Fills [UserProfile.avatarLocalPath] from persistent cache when missing.
     *
     * @param profile session profile
     * @return profile with resolved local path when file exists
     */
    private fun hydrateLocalAvatarPath(profile: UserProfile): UserProfile {
        val files = avatarFiles ?: return profile
        if (profile.avatarLocalPath.isNotBlank()) {
            val existing = java.io.File(profile.avatarLocalPath)
            if (existing.exists()) return profile
        }
        val local = files.persistentFile(profile.phoneE164)
        return if (local.exists()) {
            profile.copy(avatarLocalPath = local.absolutePath)
        } else {
            profile
        }
    }

    override suspend fun login(phoneE164: String): AuthResult {
        val normalized = RuPhone.toE164(phoneE164)
        if (normalized.isBlank()) {
            return AuthResult.ValidationError("Укажите полный номер телефона")
        }
        // Legacy clears local batteries before login request.
        batteriesStore.save(emptyList())
        lastBatteries = emptyList()

        val response = withContext(Dispatchers.IO) {
            api.request(UserAuthApi.LOGIN_PATH, phone = normalized)
        } ?: return AuthResult.NetworkError

        val error = response.json.optString("error")
        if (response.httpCode == 404 || error == "user_not_found" || !response.json.optBoolean("ok")) {
            if (error == "user_not_found" || response.httpCode == 404) {
                return AuthResult.UserNotFound
            }
            return AuthResult.ServerError(
                message = "Ошибка входа. Проверьте номер телефона.",
                httpCode = response.httpCode,
                errorCode = error,
            )
        }
        return applyAuthSuccess(response, fallbackPhone = normalized, fallbackName = "")
    }

    override suspend fun register(
        fullName: String,
        phoneE164: String,
        email: String,
        birthDate: String,
    ): AuthResult {
        val name = fullName.trim()
        if (name.isBlank()) {
            return AuthResult.ValidationError("Укажите ФИО")
        }
        val normalized = RuPhone.toE164(phoneE164)
        if (normalized.isBlank()) {
            return AuthResult.ValidationError("Укажите полный номер телефона")
        }
        batteriesStore.save(emptyList())
        lastBatteries = emptyList()

        val response = withContext(Dispatchers.IO) {
            api.request(
                path = UserAuthApi.REGISTER_PATH,
                phone = normalized,
                name = name,
                email = email,
                birth = birthDate,
            )
        } ?: return AuthResult.NetworkError

        val error = response.json.optString("error")
        if (response.httpCode == 409 || error == "phone_already_exists") {
            return AuthResult.PhoneAlreadyExists
        }
        if (response.httpCode in 200..299 && response.json.optBoolean("ok")) {
            return applyAuthSuccess(response, fallbackPhone = normalized, fallbackName = name)
        }
        return AuthResult.ServerError(
            message = registerFailureMessage(response.httpCode, error),
            httpCode = response.httpCode,
            errorCode = error,
        )
    }

    override suspend fun updateProfile(
        fullName: String,
        phoneE164: String,
        email: String,
        birthDate: String,
    ): AuthResult {
        val name = fullName.trim()
        if (name.isBlank()) {
            return AuthResult.ValidationError("Укажите ФИО")
        }
        val national = RuPhone.extractNationalDigits(phoneE164)
        if (national.length != 10) {
            return AuthResult.ValidationError("Укажите 10 цифр номера")
        }
        val normalized = RuPhone.toE164(phoneE164)
        if (normalized.isBlank()) {
            return AuthResult.ValidationError("Укажите полный номер телефона")
        }

        val previous = sessionStore.readActiveProfile()
        val localProfile = UserProfile(
            fullName = name,
            phoneE164 = normalized,
            email = email.trim(),
            birthDate = birthDate.trim(),
            avatarUrl = previous?.avatarUrl.orEmpty(),
            avatarLocalPath = previous?.avatarLocalPath.orEmpty(),
        )
        // Legacy: write locally first, then sync server.
        sessionStore.saveProfileFields(localProfile)
        _authState.value = AuthState.Authorized(localProfile)

        val response = withContext(Dispatchers.IO) {
            api.request(
                path = UserAuthApi.PROFILE_PATH,
                phone = normalized,
                name = name,
                email = email.trim(),
                birth = birthDate.trim(),
            )
        }
        if (response == null) {
            return AuthResult.ServerError(
                message = "Профиль сохранён локально, сервер недоступен",
            )
        }
        // App API-key failure — not a user JWT expiry; keep local session (legacy).
        if (response.httpCode == 401 || response.httpCode == 403) {
            return AuthResult.ServerError(
                message = "Ошибка авторизации приложения на сервере",
                httpCode = response.httpCode,
                errorCode = response.json.optString("error"),
            )
        }
        if (!response.json.optBoolean("ok")) {
            return AuthResult.ServerError(
                message = "Профиль сохранён локально, сервер недоступен",
                httpCode = response.httpCode,
                errorCode = response.json.optString("error"),
            )
        }

        val mapped = mapUserJsonToProfile(
            userJson = response.json.optJSONObject("user"),
            fallbackPhone = normalized,
            fallbackName = name,
        )
        // Do not wipe avatar when profile JSON omits avatar_url.
        val synced = mapped.copy(
            avatarLocalPath = localProfile.avatarLocalPath,
            avatarUrl = mapped.avatarUrl.ifBlank { localProfile.avatarUrl },
        )
        sessionStore.saveAuthorized(synced)
        _authState.value = AuthState.Authorized(synced)

        // Legacy: phone change triggers re-login to pull batteries for new profile.
        if (previous != null &&
            previous.phoneE164.isNotBlank() &&
            previous.phoneE164 != synced.phoneE164
        ) {
            return login(synced.phoneE164)
        }
        return AuthResult.Success(synced)
    }

    override suspend fun updateAvatar(jpegBytes: ByteArray): AuthResult {
        val current = (_authState.value as? AuthState.Authorized)?.profile
            ?: return AuthResult.ValidationError("Сначала войдите в профиль")
        if (jpegBytes.isEmpty()) {
            return AuthResult.ValidationError("Не удалось прочитать изображение")
        }
        val files = avatarFiles
            ?: return AuthResult.ServerError(message = "Хранилище аватара недоступно")

        val outFile = files.persistentFile(current.phoneE164)
        val previousBytes = withContext(Dispatchers.IO) {
            if (outFile.exists()) outFile.readBytes() else null
        }
        val previousProfile = current

        // Local preview first (legacy); roll back on upload failure.
        val previewProfile = current.copy(avatarLocalPath = outFile.absolutePath)
        withContext(Dispatchers.IO) {
            outFile.outputStream().use { it.write(jpegBytes) }
        }
        sessionStore.saveAuthorized(previewProfile)
        _authState.value = AuthState.Authorized(previewProfile)

        val response = withContext(Dispatchers.IO) {
            api.uploadAvatar(current.phoneE164, jpegBytes)
        }
        if (response == null) {
            restoreAvatarAfterFailure(outFile, previousBytes, previousProfile)
            return AuthResult.NetworkError
        }
        if (response.httpCode !in 200..299 || !response.json.optBoolean("ok")) {
            restoreAvatarAfterFailure(outFile, previousBytes, previousProfile)
            return AuthResult.ServerError(
                message = "Не удалось загрузить фото. Попробуйте еще раз.",
                httpCode = response.httpCode,
                errorCode = response.json.optString("error"),
            )
        }
        val userJson = response.json.optJSONObject("user")
        val avatarUrl = userJson?.optString("avatar_url").orEmpty().trim()
            .ifBlank { previousProfile.avatarUrl }
        val synced = previousProfile.copy(
            avatarUrl = avatarUrl,
            avatarLocalPath = outFile.absolutePath,
            // Keep text fields from previous; server user may omit them.
            fullName = userJson?.optString("name")?.takeIf { it.isNotBlank() }
                ?: previousProfile.fullName,
            email = userJson?.optString("email")?.takeIf { it.isNotBlank() }
                ?: previousProfile.email,
            birthDate = userJson?.optString("birth")?.takeIf { it.isNotBlank() }
                ?: previousProfile.birthDate,
        )
        sessionStore.saveAuthorized(synced)
        _authState.value = AuthState.Authorized(synced)
        return AuthResult.Success(synced)
    }

    override fun logout() {
        lastBatteries = emptyList()
        batteriesStore.save(emptyList())
        sessionStore.clear()
        _authState.value = AuthState.Guest
    }

    override fun lastSyncedBatteries(): List<SavedBattery> = lastBatteries

    /**
     * Clears invalid local session → Guest.
     * Used when an explicit invalid-session condition is detected.
     * Not triggered by app API-key 401 (legacy treats that as server error).
     *
     * Side effects: clears prefs + batteries; authState = Guest.
     */
    fun invalidateLocalSession() {
        logout()
    }

    private fun applyAuthSuccess(
        response: UserAuthHttpResponse,
        fallbackPhone: String,
        fallbackName: String,
    ): AuthResult.Success {
        var profile = mapUserJsonToProfile(
            userJson = response.json.optJSONObject("user"),
            fallbackPhone = fallbackPhone,
            fallbackName = fallbackName,
        )
        val files = avatarFiles
        if (files != null) {
            val local = files.persistentFile(profile.phoneE164)
            if (local.exists()) {
                profile = profile.copy(avatarLocalPath = local.absolutePath)
            }
        }
        sessionStore.saveAuthorized(profile)
        val batteries = mapServerBatteriesToSaved(response.json.optJSONArray("batteries"))
        batteriesStore.save(batteries)
        lastBatteries = batteries
        _authState.value = AuthState.Authorized(profile)
        // Legacy: after login, refresh local JPEG from server avatar_url when present.
        if (files != null && profile.avatarUrl.isNotBlank()) {
            // Fire-and-forget on IO — login already returned Success.
            // Use a blocking download here only when already on background caller;
            // applyAuthSuccess runs after withContext(IO) in login/register.
            refreshLocalAvatarFromServer(profile, files)
        }
        return AuthResult.Success(profile)
    }

    /**
     * Downloads server avatar into per-phone cache (legacy restoreProfileAvatarAfterLogin).
     *
     * @param profile authorized profile with avatarUrl
     * @param files local avatar paths
     *
     * Side effects: may rewrite local JPEG + session avatar_uri/url + authState.
     */
    private fun refreshLocalAvatarFromServer(profile: UserProfile, files: ProfileAvatarPathProvider) {
        val bytes = api.downloadAvatarBytes(profile.avatarUrl) ?: return
        val local = files.persistentFile(profile.phoneE164)
        try {
            local.outputStream().use { it.write(bytes) }
            val updated = profile.copy(avatarLocalPath = local.absolutePath)
            sessionStore.saveAuthorized(updated)
            _authState.value = AuthState.Authorized(updated)
        } catch (_: Exception) {
            // Keep previous local cache / URL.
        }
    }

    /**
     * Rolls back local avatar file + session after failed upload.
     *
     * @param outFile target file written for preview
     * @param previousBytes prior JPEG or null
     * @param previousProfile session before optimistic write
     */
    private fun restoreAvatarAfterFailure(
        outFile: java.io.File,
        previousBytes: ByteArray?,
        previousProfile: UserProfile,
    ) {
        try {
            if (previousBytes != null) {
                outFile.outputStream().use { it.write(previousBytes) }
            } else if (outFile.exists()) {
                outFile.delete()
            }
        } catch (_: Exception) {
            // Best-effort rollback.
        }
        sessionStore.saveAuthorized(previousProfile)
        _authState.value = AuthState.Authorized(previousProfile)
    }
}
