package ru.liferych.bms.data.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liferych.bms.domain.support.SupportTicket
import ru.liferych.bms.domain.support.SupportTicketStatus

class SupportMapperTest {

    @Test
    fun status_doneAndClosedMapToClosed() {
        assertEquals(SupportTicketStatus.Closed, SupportStatusMapper.toDomain("done"))
        assertEquals(SupportTicketStatus.Closed, SupportStatusMapper.toDomain("closed"))
        assertEquals(SupportTicketStatus.Open, SupportStatusMapper.toDomain("new"))
        assertEquals(SupportTicketStatus.Open, SupportStatusMapper.toDomain("in_work"))
        assertEquals("Закрыто", SupportStatusMapper.toRu("done"))
        assertEquals("Открыто", SupportStatusMapper.toRu("sent"))
    }

    @Test
    fun mapRemote_usesServerIdAndFields() {
        val ticket = mapRemoteToTicket(
            serverId = "42",
            remoteStatus = "in_work",
            adminComment = "ok",
            updatedAt = "2026-01-02",
            createdAt = "2026-01-01",
            bmsUid = "AA:BB",
            model = "LiFe",
            problem = "Проблема",
            fio = "Иван",
            phone = "+79001112233",
            existing = null,
        )
        assertEquals("server_42", ticket.localId)
        assertEquals("42", ticket.serverId)
        assertEquals(SupportTicketStatus.Open, ticket.status)
        assertTrue(ticket.listPageItem)
        assertEquals("Иван", ticket.fio)
    }

    @Test
    fun mapRemote_preservesExistingLocalId() {
        val existing = SupportTicket(localId = "local_1", serverId = "42", fio = "Old")
        val ticket = mapRemoteToTicket(
            serverId = "42",
            remoteStatus = "done",
            adminComment = "",
            updatedAt = "",
            createdAt = "",
            bmsUid = "AA",
            model = "",
            problem = "",
            fio = "",
            phone = "",
            existing = existing,
        )
        assertEquals("local_1", ticket.localId)
        assertEquals(SupportTicketStatus.Closed, ticket.status)
        assertEquals("Old", ticket.fio)
    }

    @Test
    fun localCacheJsonRoundTrip() {
        val original = SupportTicket(
            localId = "local_9",
            serverId = "9",
            statusRaw = "new",
            status = SupportTicketStatus.Open,
            createdAt = "2026-01-01 12:00:00",
            model = "M",
            problem = "P",
            fio = "F",
            phone = "+7900",
            listPageItem = true,
        )
        val restored = SupportLocalCache.fromJson(SupportLocalCache.toJson(original))
        assertEquals(original.localId, restored.localId)
        assertEquals(original.serverId, restored.serverId)
        assertEquals(original.problem, restored.problem)
        assertTrue(restored.listPageItem)
        assertFalse(SupportStatusMapper.isClosed(restored.statusRaw))
    }
}
