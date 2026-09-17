package ru.liferych.bms.data.support

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.support.SupportRepository
import ru.liferych.bms.domain.support.SupportResult
import ru.liferych.bms.domain.support.SupportSubmitDraft
import ru.liferych.bms.domain.support.SupportTicket
import ru.liferych.bms.domain.support.SupportTicketPage
import java.util.Calendar

/**
 * Support repository implementing legacy warranty list/submit + local cache.
 */
class SupportRepositoryImpl(
    private val api: SupportApi,
    private val cache: SupportTicketCache,
    private val mediaEncoder: SupportMediaEncoding,
) : SupportRepository {

    private val _tickets = MutableStateFlow(cache.loadAll())
    override val tickets: StateFlow<List<SupportTicket>> = _tickets.asStateFlow()

    private val _listLoading = MutableStateFlow(false)
    override val listLoading: StateFlow<Boolean> = _listLoading.asStateFlow()

    private val _listError = MutableStateFlow("")
    override val listError: StateFlow<String> = _listError.asStateFlow()

    private val _listPage = MutableStateFlow(1)
    override val listPage: StateFlow<Int> = _listPage.asStateFlow()

    private val _listTotal = MutableStateFlow(0)
    override val listTotal: StateFlow<Int> = _listTotal.asStateFlow()

    private val _listTotalPages = MutableStateFlow(1)
    override val listTotalPages: StateFlow<Int> = _listTotalPages.asStateFlow()

    override suspend fun refreshTickets(bmsUid: String, page: Int): SupportResult {
        val uid = bmsUid.trim()
        if (uid.isBlank() || uid == "unknown_bms") {
            return SupportResult.ValidationError("BMS не определена")
        }
        _listLoading.value = true
        _listError.value = ""
        _listPage.value = page.coerceAtLeast(1)

        val response = withContext(Dispatchers.IO) {
            api.listTickets(uid, _listPage.value, SupportApi.PAGE_SIZE)
        }
        if (response == null) {
            _listLoading.value = false
            _listError.value = "Не удалось связаться с сервером. Проверьте подключение к интернету."
            return SupportResult.NetworkError
        }

        val err = response.json.optString("error")
        if (response.httpCode == 401 || err == "unauthorized") {
            _listLoading.value = false
            val message = "Не удалось обновить статусы: нет доступа к серверу"
            _listError.value = message
            return SupportResult.Unauthorized(message)
        }
        if (response.httpCode !in 200..299 || !response.json.optBoolean("ok")) {
            _listLoading.value = false
            val message = "Не удалось обновить статусы обращений. Попробуйте ещё раз."
            _listError.value = message
            return SupportResult.ServerError(message, response.httpCode)
        }

        val remoteList = response.requestsArray()
        _listTotal.value = response.json.optInt("total", remoteList.length())
        _listTotalPages.value = maxOf(1, response.json.optInt("total_pages", 1))
        _listPage.value = response.json.optInt("page", _listPage.value)
            .coerceIn(1, _listTotalPages.value)

        val merged = cache.clearPageFlags().toMutableList()
        for (i in 0 until remoteList.length()) {
            val remote = remoteList.optJSONObject(i) ?: continue
            val serverId = remote.optString("id")
            if (serverId.isBlank()) continue
            val existing = merged.firstOrNull { it.serverId == serverId }
            val mapped = mapRemoteToTicket(
                serverId = serverId,
                remoteStatus = remote.optString("status", existing?.statusRaw ?: "new"),
                adminComment = remote.optString("admin_comment", existing?.adminComment.orEmpty()),
                updatedAt = remote.optString("updated_at", existing?.updatedAt.orEmpty()),
                createdAt = remote.optString("created_at", existing?.createdAt.orEmpty()),
                bmsUid = remote.optString("bms_uid", uid),
                model = remote.optString("battery_model", existing?.model.orEmpty()),
                problem = remote.optString("problem_text", existing?.problem.orEmpty()),
                fio = remote.optString("client_fio", existing?.fio.orEmpty()),
                phone = remote.optString("client_phone", existing?.phone.orEmpty()),
                existing = existing,
            )
            val idx = merged.indexOfFirst { it.serverId == serverId || it.localId == mapped.localId }
            if (idx >= 0) merged[idx] = mapped else merged += mapped
        }
        cache.saveAll(merged)
        _tickets.value = merged
        _listLoading.value = false
        _listError.value = ""
        return SupportResult.ListSuccess(
            SupportTicketPage(
                tickets = ticketsForUi(uid, _listPage.value),
                total = _listTotal.value,
                page = _listPage.value,
                totalPages = _listTotalPages.value,
            ),
        )
    }

    override suspend fun submitTicket(
        draft: SupportSubmitDraft,
        mediaUris: List<Uri>,
        batterySnapshotJson: JSONObject?,
        configSnapshotJson: JSONObject?,
    ): SupportResult {
        val fio = draft.fio.trim()
        val phone = draft.phone.trim()
        val problem = draft.problem.trim()
        val model = draft.model.trim().ifBlank { "LiFePO4 АКБ" }
        if (fio.isBlank() || !RuPhone.isValidRu(phone) || problem.isBlank()) {
            return SupportResult.ValidationError("Заполните ФИО, полный телефон и описание проблемы")
        }

        val mediaArray = JSONArray()
        val encodeFailed = withContext(Dispatchers.IO) {
            mediaUris.take(SupportMediaEncoder.MAX_ATTACHMENTS).forEachIndexed { idx, uri ->
                val encoded = mediaEncoder.encode(uri) ?: return@withContext true
                mediaArray.put(
                    JSONObject().apply {
                        put("filename", mediaEncoder.displayName(uri, idx))
                        put("mime", encoded.second)
                        put("data_base64", encoded.first)
                    },
                )
            }
            false
        }
        if (encodeFailed) {
            return SupportResult.ValidationError("Не удалось прочитать вложение. Проверьте выбранные файлы.")
        }

        val localTicket = SupportTicket(
            localId = draft.localId,
            serverId = draft.serverId,
            statusRaw = "new",
            status = SupportStatusMapper.toDomain("new"),
            createdAt = draft.createdAt.ifBlank { nowText() },
            updatedAt = nowText(),
            bmsUid = draft.bmsUid,
            model = model,
            problem = problem,
            fio = fio,
            phone = phone,
        )
        cache.upsert(localTicket)
        refreshTicketsFlow()

        val payload = JSONObject().apply {
            put("client_fio", fio)
            put("client_phone", phone)
            put("battery_model", model)
            put("problem_text", problem)
            put("bms_uid", draft.bmsUid)
            put("bluetooth_name", draft.bluetoothName)
            put("bluetooth_address", draft.bluetoothAddress)
            if (draft.bmsSn.isNotBlank()) put("bms_sn", draft.bmsSn)
            put("app_version", draft.appVersion)
            put("status", "new")
            if (batterySnapshotJson != null) put("battery_snapshot", batterySnapshotJson)
            if (configSnapshotJson != null) put("config_snapshot", configSnapshotJson)
            if (mediaArray.length() > 0) put("media", mediaArray)
            if (draft.serverId.isNotBlank()) put("request_id", draft.serverId)
        }

        val response = withContext(Dispatchers.IO) {
            api.submitTicket(payload)
        } ?: return SupportResult.NetworkError.also {
            cache.upsert(localTicket.copy(statusRaw = "failed", updatedAt = nowText()))
            refreshTicketsFlow()
        }

        val err = response.json.optString("error")
        if (response.httpCode == 401 || err == "unauthorized") {
            cache.upsert(localTicket.copy(statusRaw = "failed", updatedAt = nowText()))
            refreshTicketsFlow()
            return SupportResult.Unauthorized("Не удалось отправить обращение: нет доступа к серверу")
        }
        if (response.httpCode !in 200..299 || !response.json.optBoolean("ok")) {
            cache.upsert(localTicket.copy(statusRaw = "failed", updatedAt = nowText()))
            refreshTicketsFlow()
            return SupportResult.ServerError(
                message = "Не удалось отправить обращение. Попробуйте ещё раз.",
                httpCode = response.httpCode,
            )
        }

        val id = response.json.opt("id")?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
            .orEmpty()
        val success = localTicket.copy(
            serverId = id.ifBlank { localTicket.serverId },
            statusRaw = "new",
            status = SupportStatusMapper.toDomain("new"),
            updatedAt = nowText(),
        )
        cache.upsert(success)
        refreshTicketsFlow()
        return SupportResult.SubmitSuccess(success)
    }

    override fun findByLocalId(localId: String): SupportTicket? {
        return _tickets.value.firstOrNull { it.localId == localId }
    }

    override fun ticketsForUi(bmsUid: String, page: Int): List<SupportTicket> {
        val uid = bmsUid.trim()
        val all = _tickets.value.filter { item ->
            val itemUid = item.bmsUid
            itemUid.isBlank() || itemUid == uid
        }
        val pageItems = all.filter { it.listPageItem }
            .sortedWith(
                compareByDescending<SupportTicket> { it.serverId.toIntOrNull() ?: 0 }
                    .thenByDescending { it.createdAt },
            )
        val drafts = if (page <= 1) {
            all.filter { it.serverId.isBlank() }
                .sortedByDescending { it.createdAt }
        } else {
            emptyList()
        }
        return drafts + pageItems
    }

    private fun refreshTicketsFlow() {
        _tickets.value = cache.loadAll()
    }

    private fun nowText(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND),
        )
    }
}
