package ru.liferych.bms.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWriteVerifierTest {
    @Test
    fun verify_acceptsExactRawValue() {
        val result = RemoteWriteVerifier.verify(
            command = command(rawValue = 360, value = 3600.0, scale = 0.1),
            raw = 360,
            runtimeSoc = null,
        )

        assertTrue(result.confirmed)
    }

    @Test
    fun verify_usesHalfLsbCappedAtPointZeroTwo() {
        val command = command(rawValue = 3650, value = 3.650, scale = 1000.0)

        assertTrue(
            RemoteWriteVerifier.verify(command, raw = 3650, runtimeSoc = null).confirmed,
        )
        assertFalse(
            RemoteWriteVerifier.verify(command, raw = 3649, runtimeSoc = null).confirmed,
        )
    }

    @Test
    fun verify_runtimeSocUsesLegacyOnePercentTolerance() {
        val command = command(
            key = "runtime_soc",
            rawValue = 900,
            value = 90.0,
            scale = 10.0,
        )

        assertTrue(RemoteWriteVerifier.verify(command, raw = null, runtimeSoc = 89.0).confirmed)
        assertFalse(RemoteWriteVerifier.verify(command, raw = null, runtimeSoc = 88.9).confirmed)
        assertEquals(
            89.0,
            RemoteWriteVerifier.verify(command, raw = 900, runtimeSoc = 89.0).actual!!,
            0.0,
        )
    }

    @Test
    fun verify_mosUsesExactDiscreteMatch() {
        val command = command(
            key = "charge_mos",
            register = 0x00A5,
            registerText = "0x00A5",
            rawValue = 1,
            value = 1.0,
            scale = 1.0,
        )

        assertTrue(RemoteWriteVerifier.verify(command, raw = 1, runtimeSoc = null).confirmed)
        assertFalse(RemoteWriteVerifier.verify(command, raw = 0, runtimeSoc = null).confirmed)
        assertFalse(RemoteWriteVerifier.verify(command, raw = null, runtimeSoc = null).confirmed)
    }

    private fun command(
        key: String = "sleep_timeout",
        register: Int = 0x0115,
        registerText: String = "0x0115",
        rawValue: Int,
        value: Double,
        scale: Double,
    ): RemoteWriteCommand {
        return RemoteWriteCommand(
            id = 1,
            bmsUid = "DL-AABBCCDDEEFF",
            key = key,
            label = key,
            register = register,
            registerText = registerText,
            unit = "",
            value = value,
            rawValue = rawValue,
            scale = scale,
            offset = 0.0,
            status = "pending",
            actual = null,
            error = null,
            createdAt = 1,
            updatedAt = 1,
            leaseToken = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            expiresAt = 2,
        )
    }
}
