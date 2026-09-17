package ru.liferych.bms.data.auth

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.liferych.bms.data.local.SavedBattery
import ru.liferych.bms.domain.auth.AuthResult
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.auth.UserProfile
import ru.liferych.bms.ui.screens.profile.isProfileSaveReady
import java.io.File

/**
 * Auth session restore / login / logout / avatar without real network or Android prefs.
 */
class UserAuthRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sessionStore: InMemoryAuthSessionStore
    private lateinit var batteries: MutableList<SavedBattery>
    private lateinit var repository: UserAuthRepositoryImpl
    private lateinit var avatarDir: File
    private var nextResponse: UserAuthHttpResponse? = null
    private var nextAvatarResponse: UserAuthHttpResponse? = null
    private var lastAvatarJpegSize: Int = -1

    @Before
    fun setUp() {
        sessionStore = InMemoryAuthSessionStore()
        batteries = mutableListOf()
        nextResponse = null
        nextAvatarResponse = null
        lastAvatarJpegSize = -1
        avatarDir = tempFolder.newFolder("profile_avatars")
        val api = UserAuthApi(
            baseUrlProvider = { "http://127.0.0.1" },
            apiKeyProvider = { "test-key" },
            requestExecutor = UserAuthRequestExecutor { _, _, _, _, _ -> nextResponse },
            avatarExecutor = UserAuthAvatarExecutor { _, jpegBytes ->
                lastAvatarJpegSize = jpegBytes.size
                nextAvatarResponse
            },
        )
        val avatarPaths = ProfileAvatarPathProvider { phone ->
            val digits = phone.filter { it.isDigit() }
            File(avatarDir, "avatar_$digits.jpg")
        }
        repository = UserAuthRepositoryImpl(
            sessionStore = sessionStore,
            api = api,
            batteriesStore = AuthBatteriesStore { items ->
                batteries.clear()
                batteries.addAll(items)
            },
            avatarFiles = avatarPaths,
        )
    }

    @Test
    fun restoreSession_guestWhenEmpty() {
        repository.restoreSession()
        assertEquals(AuthState.Guest, repository.authState.value)
    }

    @Test
    fun restoreSession_authorizedWhenPrefsValid() {
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Тест",
                phoneE164 = "+79001112233",
                avatarUrl = "http://x/a.jpg",
                avatarLocalPath = "/tmp/avatar.jpg",
            ),
        )
        repository.restoreSession()
        val state = repository.authState.value
        assertTrue(state is AuthState.Authorized)
        val profile = (state as AuthState.Authorized).profile
        assertEquals("Тест", profile.fullName)
        assertEquals("http://x/a.jpg", profile.avatarUrl)
        assertEquals("/tmp/avatar.jpg", profile.avatarLocalPath)
    }

    @Test
    fun login_successUpdatesAuthState() = runBlocking {
        nextResponse = UserAuthHttpResponse(
            httpCode = 200,
            json = JSONObject(
                """{"ok":true,"user":{"name":"Иван","phone":"+79001234567","email":"","birth":""},"batteries":[]}""",
            ),
        )
        val result = repository.login("+79001234567")
        assertTrue(result is AuthResult.Success)
        assertTrue(repository.authState.value is AuthState.Authorized)
        assertEquals(
            "+79001234567",
            (repository.authState.value as AuthState.Authorized).profile.phoneE164,
        )
    }

    @Test
    fun logout_clearsSessionToGuest() {
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Тест",
                phoneE164 = "+79001112233",
                avatarUrl = "http://x/a.jpg",
                avatarLocalPath = "/tmp/a.jpg",
            ),
        )
        batteries += SavedBattery(
            address = "AA:BB:CC:DD:EE:01",
            bluetoothName = "x",
            customName = "",
            soc = null,
            capacityAh = null,
            lastSeenAt = 1L,
        )
        repository.restoreSession()
        repository.logout()
        assertEquals(AuthState.Guest, repository.authState.value)
        assertEquals(null, sessionStore.readActiveProfile())
        assertTrue(batteries.isEmpty())
    }

    @Test
    fun logout_clearsAvatarMetadataFromSession() {
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Тест",
                phoneE164 = "+79001112233",
                avatarUrl = "http://x/a.jpg",
                avatarLocalPath = "/tmp/a.jpg",
            ),
        )
        repository.restoreSession()
        repository.logout()
        assertEquals(null, sessionStore.readActiveProfile())
        assertTrue(sessionStore.wasCleared)
    }

    @Test
    fun invalidateLocalSession_goesGuest() {
        sessionStore.saveAuthorized(
            UserProfile(fullName = "Тест", phoneE164 = "+79001112233"),
        )
        repository.restoreSession()
        repository.invalidateLocalSession()
        assertEquals(AuthState.Guest, repository.authState.value)
    }

    @Test
    fun profileValidation_requiresNameAndPhone() {
        assertTrue(!isProfileSaveReady("", "9001234567"))
        assertTrue(!isProfileSaveReady("Иван", "123"))
        assertTrue(isProfileSaveReady("Иван", RuPhone.formatNationalMask("9001234567")))
    }

    @Test
    fun updateAvatar_successUpdatesAuthStateAndSession() = runBlocking {
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Иван",
                phoneE164 = "+79001234567",
                email = "a@b.ru",
                birthDate = "01.01.1990",
                avatarUrl = "http://old/avatar.jpg",
            ),
        )
        repository.restoreSession()
        nextAvatarResponse = UserAuthHttpResponse(
            httpCode = 200,
            json = JSONObject(
                """{"ok":true,"user":{"name":"Иван","phone":"+79001234567","avatar_url":"http://new/avatar.jpg"}}""",
            ),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val result = repository.updateAvatar(jpeg)
        assertTrue(result is AuthResult.Success)
        val profile = (repository.authState.value as AuthState.Authorized).profile
        assertEquals("http://new/avatar.jpg", profile.avatarUrl)
        assertEquals("Иван", profile.fullName)
        assertEquals("a@b.ru", profile.email)
        assertEquals("01.01.1990", profile.birthDate)
        assertTrue(profile.avatarLocalPath.isNotBlank())
        assertTrue(File(profile.avatarLocalPath).exists())
        assertEquals(4, lastAvatarJpegSize)
        assertEquals("http://new/avatar.jpg", sessionStore.readActiveProfile()?.avatarUrl)
    }

    @Test
    fun updateAvatar_networkErrorKeepsPreviousAvatar() = runBlocking {
        val previousFile = File(avatarDir, "avatar_79001234567.jpg")
        previousFile.writeBytes(byteArrayOf(0x11, 0x22))
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Иван",
                phoneE164 = "+79001234567",
                avatarUrl = "http://old/avatar.jpg",
                avatarLocalPath = previousFile.absolutePath,
            ),
        )
        repository.restoreSession()
        nextAvatarResponse = null
        val result = repository.updateAvatar(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33))
        assertTrue(result is AuthResult.NetworkError)
        val profile = (repository.authState.value as AuthState.Authorized).profile
        assertEquals("http://old/avatar.jpg", profile.avatarUrl)
        assertEquals(previousFile.absolutePath, profile.avatarLocalPath)
        assertEquals(0x11.toByte(), previousFile.readBytes()[0])
    }

    @Test
    fun updateAvatar_serverErrorKeepsPreviousAvatar() = runBlocking {
        val previousFile = File(avatarDir, "avatar_79001234567.jpg")
        previousFile.writeBytes(byteArrayOf(0xAA.toByte()))
        sessionStore.saveAuthorized(
            UserProfile(
                fullName = "Иван",
                phoneE164 = "+79001234567",
                avatarUrl = "http://old/a.jpg",
                avatarLocalPath = previousFile.absolutePath,
            ),
        )
        repository.restoreSession()
        nextAvatarResponse = UserAuthHttpResponse(
            httpCode = 500,
            json = JSONObject("""{"ok":false,"error":"server"}"""),
        )
        val result = repository.updateAvatar(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertTrue(result is AuthResult.ServerError)
        assertEquals(
            "http://old/a.jpg",
            (repository.authState.value as AuthState.Authorized).profile.avatarUrl,
        )
        assertEquals(0xAA.toByte(), previousFile.readBytes()[0])
    }

    @Test
    fun updateAvatar_requiresAuthorizedSession() = runBlocking {
        repository.restoreSession()
        val result = repository.updateAvatar(byteArrayOf(1, 2, 3))
        assertTrue(result is AuthResult.ValidationError)
    }
}

/** In-memory [AuthSessionStorage] for JVM unit tests. */
private class InMemoryAuthSessionStore : AuthSessionStorage {
    private var profile: UserProfile? = null
    private var loggedIn: Boolean = false

    /** True after [clear] — logout must wipe avatar metadata with the session. */
    var wasCleared: Boolean = false

    override fun readActiveProfile(): UserProfile? {
        val p = profile ?: return null
        if (!loggedIn || !RuPhone.isValidRu(p.phoneE164) || p.fullName.isBlank()) return null
        return p
    }

    override fun saveAuthorized(profile: UserProfile) {
        this.profile = profile
        loggedIn = true
    }

    override fun saveProfileFields(profile: UserProfile) {
        this.profile = profile
        loggedIn = true
    }

    override fun clear() {
        wasCleared = true
        profile = null
        loggedIn = false
    }
}
