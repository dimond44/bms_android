package ru.liferych.bms.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteWriteCommandTest {
    @Test
    fun fromJson_preservesServerRegisterAndRawValue() {
        val command = RemoteWriteCommand.fromJson(
            JSONObject(
                """
                {
                  "id": 42,
                  "bms_uid": "DL-4119040183E6",
                  "key": "sleep_timeout",
                  "label": "Время ожидания сна",
                  "register": "0x0115",
                  "unit": "s",
                  "value": 3600,
                  "raw_value": 360,
                  "scale": 0.1,
                  "offset": 0,
                  "status": "writing",
                  "lease_token": "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
                  "actual": null,
                  "error": null,
                  "created_at": 100,
                  "updated_at": 200,
                  "expires_at": 300
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(command)
        assertEquals(42L, command.id)
        assertEquals("DL-4119040183E6", command.bmsUid)
        assertEquals(0x0115, command.register)
        assertEquals(360, command.rawValue)
        assertEquals(3600.0, command.value, 0.0)
    }

    @Test
    fun fromJson_rejectsMissingBmsUidAndInvalidRawValue() {
        val missingUid = validJson().apply { remove("bms_uid") }
        val outOfRangeRaw = validJson().apply { put("raw_value", 0x1_0000) }

        assertNull(RemoteWriteCommand.fromJson(missingUid))
        assertNull(RemoteWriteCommand.fromJson(outOfRangeRaw))
    }

    private fun validJson(): JSONObject = JSONObject().apply {
        put("id", 1)
        put("bms_uid", "DL-AABBCCDDEEFF")
        put("key", "sleep_timeout")
        put("label", "Время ожидания сна")
        put("register", "0x0115")
        put("unit", "s")
        put("value", 3600.0)
        put("raw_value", 360)
        put("scale", 0.1)
        put("offset", 0.0)
        put("status", "writing")
        put("lease_token", "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899")
        put("created_at", 1L)
        put("updated_at", 1L)
        put("expires_at", 2L)
    }
}
