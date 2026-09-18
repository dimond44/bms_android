package ru.liferych.bms.data.auth

import android.content.Context
import android.content.SharedPreferences
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.auth.UserProfile

/**
 * Local session storage compatible with legacy MainActivity `user_profile` prefs.
 *
 * Keys: logged_in, name, phone, email, birth, avatar_url, avatar_uri.
 * No token/JWT — phone + logged_in flag is the session.
 * Prefs are MODE_PRIVATE but not encrypted; allowBackup=true can expose them.
 */
interface AuthSessionStorage {
    /**
     * Reads stored profile if session is active (same rules as legacy isUserSessionActive).
     *
     * @return UserProfile or null when guest
     */
    fun readActiveProfile(): UserProfile?

    /**
     * Persists authorized session fields after login/register/profile sync.
     *
     * @param profile domain profile (phone as E.164)
     */
    fun saveAuthorized(profile: UserProfile)

    /**
     * Writes profile fields locally without requiring network success.
     *
     * @param profile fields to store; keeps logged_in true
     */
    fun saveProfileFields(profile: UserProfile)

    /**
     * Clears entire user_profile prefs (legacy logout).
     */
    fun clear()
}

/**
 * SharedPreferences-backed [AuthSessionStorage].
 */
class AuthSessionStore(
    context: Context,
) : AuthSessionStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readActiveProfile(): UserProfile? {
        if (!prefs.getBoolean(KEY_LOGGED_IN, false)) return null
        val phone = prefs.getString(KEY_PHONE, "")?.trim().orEmpty()
        val name = prefs.getString(KEY_NAME, "")?.trim().orEmpty()
        if (!RuPhone.isValidRu(phone) || name.isBlank()) return null
        return UserProfile(
            fullName = name,
            phoneE164 = RuPhone.toE164(phone).ifBlank { phone },
            email = prefs.getString(KEY_EMAIL, "")?.trim().orEmpty(),
            birthDate = prefs.getString(KEY_BIRTH, "")?.trim().orEmpty(),
            avatarUrl = prefs.getString(KEY_AVATAR_URL, "")?.trim().orEmpty(),
            avatarLocalPath = prefs.getString(KEY_AVATAR_URI, "")?.trim().orEmpty(),
        )
    }

    override fun saveAuthorized(profile: UserProfile) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_NAME, profile.fullName)
            .putString(KEY_PHONE, profile.phoneE164)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_BIRTH, profile.birthDate)
            .putString(KEY_AVATAR_URL, profile.avatarUrl)
            .putString(KEY_AVATAR_URI, profile.avatarLocalPath)
            .apply()
    }

    override fun saveProfileFields(profile: UserProfile) {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_NAME, profile.fullName)
            .putString(KEY_PHONE, profile.phoneE164)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_BIRTH, profile.birthDate)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "user_profile"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_EMAIL = "email"
        const val KEY_BIRTH = "birth"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_AVATAR_URI = "avatar_uri"
    }
}
