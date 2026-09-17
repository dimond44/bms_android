package ru.liferych.bms.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class BmsIdentityStoreTest {
    @Test
    fun displayFactorySerialStripsTrailingVersionToken() {
        assertEquals(
            "224LG151200441",
            BmsIdentityStore.displayFactorySerial("224LG151200441R24TK"),
        )
    }

    @Test
    fun displayFactorySerialKeepsCleanValue() {
        assertEquals(
            "224LG151200441",
            BmsIdentityStore.displayFactorySerial("224LG151200441"),
        )
    }

    @Test
    fun displayFactorySerialBlankBecomesEmpty() {
        assertEquals("", BmsIdentityStore.displayFactorySerial("  "))
        assertEquals("", BmsIdentityStore.displayFactorySerial(null))
    }
}
