package ru.liferych.bms.data.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liferych.bms.domain.auth.RuPhone

/**
 * Unit tests for profile DTO mapping and phone helpers (no network).
 */
class UserAuthMappingTest {

    @Test
    fun mapUserJson_usesServerFields() {
        val user = JSONObject(
            """{"name":"Иван","phone":"+79001234567","email":"a@b.ru","birth":"01.01.1990","avatar_url":"http://x/a.jpg"}""",
        )
        val profile = mapUserJsonToProfile(user, fallbackPhone = "+79999999999", fallbackName = "X")
        assertEquals("Иван", profile.fullName)
        assertEquals("+79001234567", profile.phoneE164)
        assertEquals("a@b.ru", profile.email)
        assertEquals("01.01.1990", profile.birthDate)
        assertEquals("http://x/a.jpg", profile.avatarUrl)
    }

    @Test
    fun mapUserJson_blankAvatarUrlDefaultsEmpty() {
        val user = JSONObject(
            """{"name":"Иван","phone":"+79001234567"}""",
        )
        val profile = mapUserJsonToProfile(user, fallbackPhone = "+79001234567", fallbackName = "Иван")
        assertEquals("", profile.avatarUrl)
        assertEquals("", profile.avatarLocalPath)
    }

    @Test
    fun mapUserJson_fallsBackWhenUserMissing() {
        val profile = mapUserJsonToProfile(null, fallbackPhone = "+79001112233", fallbackName = "Гость")
        assertEquals("Гость", profile.fullName)
        assertEquals("+79001112233", profile.phoneE164)
    }

    @Test
    fun mapServerBatteries_dedupesByAddress() {
        val arr = org.json.JSONArray(
            """[
              {"bluetooth_address":"AA:BB:CC:DD:EE:01","advertised_name":"A","last_seen_at":2},
              {"bluetooth_address":"aa:bb:cc:dd:ee:01","advertised_name":"B","last_seen_at":1}
            ]""",
        )
        val list = mapServerBatteriesToSaved(arr)
        assertEquals(1, list.size)
        assertEquals("AA:BB:CC:DD:EE:01", list[0].address)
    }

    @Test
    fun ruPhone_nationalAndE164() {
        assertEquals("9001234567", RuPhone.extractNationalDigits("+7 (900) 123-45-67"))
        assertEquals("+79001234567", RuPhone.toE164("9001234567"))
        assertEquals("900 123-45-67", RuPhone.formatNationalMask("9001234567"))
        assertTrue(RuPhone.isValidRu("9001234567"))
    }

    @Test
    fun registerFailureMessage_unauthorized() {
        assertEquals(
            "Ошибка авторизации приложения на сервере",
            registerFailureMessage(401, "unauthorized"),
        )
    }

    @Test
    fun registerFailureMessage_forbiddenAndConflictAndServer() {
        assertEquals(
            "Ошибка авторизации приложения на сервере",
            registerFailureMessage(403, "forbidden"),
        )
        assertEquals(
            "Пользователь с таким номером уже зарегистрирован",
            registerFailureMessage(409, "phone_already_exists"),
        )
        assertEquals(
            "Ошибка сервера. Повторите позже.",
            registerFailureMessage(500, ""),
        )
        assertEquals(
            "Некорректный номер телефона",
            registerFailureMessage(400, "invalid_phone"),
        )
    }

    @Test
    fun ruPhone_masksUiInputToE164() {
        // UI shows "+7" prefix separately and national mask "963 081-85-26"
        assertEquals("+79630818526", RuPhone.toE164("963 081-85-26"))
        assertEquals("+79630818526", RuPhone.toE164("+7 963 081-85-26"))
        assertEquals("+79630818526", RuPhone.toE164("89630818526"))
    }
}
