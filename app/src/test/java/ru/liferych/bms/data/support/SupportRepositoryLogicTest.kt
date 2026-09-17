package ru.liferych.bms.data.support

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.liferych.bms.domain.support.SupportResult
import ru.liferych.bms.domain.support.SupportSubmitDraft
import ru.liferych.bms.domain.support.SupportTicket

/**
 * Support repository behaviour without real HTTP / Android Context.
 */
class SupportRepositoryLogicTest {

    private lateinit var cache: InMemorySupportCache
    private lateinit var repository: SupportRepositoryImpl
    private var nextResponse: SupportHttpResponse? = null

    @Before
    fun setUp() {
        cache = InMemorySupportCache()
        nextResponse = null
        val api = SupportApi(
            baseUrlProvider = { "http://127.0.0.1" },
            apiKeyProvider = { "test-key" },
            executor = SupportRequestExecutor { _, _ -> nextResponse },
        )
        repository = SupportRepositoryImpl(
            api = api,
            cache = cache,
            mediaEncoder = object : SupportMediaEncoding {
                override fun encode(uri: Uri): Pair<String, String>? = null
                override fun displayName(uri: Uri, index: Int): String = "file_$index"
            },
        )
    }

    @Test
    fun list_emptySuccess() = runBlocking {
        nextResponse = SupportHttpResponse(
            httpCode = 200,
            json = JSONObject("""{"ok":true,"requests":[],"total":0,"page":1,"total_pages":1}"""),
        )
        val result = repository.refreshTickets("AA:BB:CC:DD:EE:01", page = 1)
        assertTrue(result is SupportResult.ListSuccess)
        val page = (result as SupportResult.ListSuccess).page
        assertEquals(0, page.total)
        assertTrue(page.tickets.isEmpty())
    }

    @Test
    fun submit_validationRequiresFields() = runBlocking {
        val result = repository.submitTicket(
            draft = SupportSubmitDraft(
                localId = "local_1",
                fio = "",
                phone = "900",
                model = "M",
                problem = "",
                bmsUid = "AA",
                appVersion = "0.1",
            ),
            mediaUris = emptyList(),
            batterySnapshotJson = null,
        )
        assertTrue(result is SupportResult.ValidationError)
    }

    @Test
    fun submit_successUpdatesCache() = runBlocking {
        nextResponse = SupportHttpResponse(
            httpCode = 200,
            json = JSONObject("""{"ok":true,"id":77}"""),
        )
        val result = repository.submitTicket(
            draft = SupportSubmitDraft(
                localId = "local_2",
                fio = "Иван",
                phone = "+79001234567",
                model = "LiFe",
                problem = "Не заряжается",
                bmsUid = "AA:BB",
                appVersion = "0.1",
            ),
            mediaUris = emptyList(),
            batterySnapshotJson = JSONObject().put("soc", 50),
        )
        assertTrue(result is SupportResult.SubmitSuccess)
        val ticket = (result as SupportResult.SubmitSuccess).ticket
        assertEquals("77", ticket.serverId)
        assertEquals("Иван", cache.loadAll().first().fio)
    }

    @Test
    fun list_requiresBmsUid() = runBlocking {
        val result = repository.refreshTickets("unknown_bms")
        assertTrue(result is SupportResult.ValidationError)
    }
}

private class InMemorySupportCache : SupportTicketCache {
    private val items = mutableListOf<SupportTicket>()

    override fun loadAll(): List<SupportTicket> = items.toList()

    override fun saveAll(tickets: List<SupportTicket>) {
        items.clear()
        items.addAll(tickets)
    }

    override fun upsert(ticket: SupportTicket) {
        val idx = items.indexOfFirst { it.localId == ticket.localId }
        if (idx >= 0) items[idx] = ticket else items += ticket
    }

    override fun clearPageFlags(): List<SupportTicket> {
        val cleared = items.map { it.copy(listPageItem = false) }
        items.clear()
        items.addAll(cleared)
        return cleared
    }
}
