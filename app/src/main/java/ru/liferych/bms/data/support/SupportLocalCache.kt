package ru.liferych.bms.data.support

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.domain.support.SupportTicket

/**
 * Local warranty cache — same prefs file as legacy MainActivity (`warranty_requests_cache`).
 */
interface SupportTicketCache {
    fun loadAll(): List<SupportTicket>
    fun saveAll(tickets: List<SupportTicket>)
    fun upsert(ticket: SupportTicket)
    fun clearPageFlags(): List<SupportTicket>
}

/**
 * SharedPreferences-backed [SupportTicketCache].
 */
class SupportLocalCache(
    context: Context,
) : SupportTicketCache {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAll(): List<SupportTicket> {
        val arr = readArray()
        val out = mutableListOf<SupportTicket>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out += fromJson(item)
        }
        return out
    }

    override fun saveAll(tickets: List<SupportTicket>) {
        val arr = JSONArray()
        tickets.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_REQUESTS, arr.toString()).apply()
    }

    override fun upsert(ticket: SupportTicket) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.localId == ticket.localId }
        if (idx >= 0) all[idx] = ticket else all += ticket
        saveAll(all)
    }

    override fun clearPageFlags(): List<SupportTicket> {
        return loadAll().map { it.copy(listPageItem = false) }.also { saveAll(it) }
    }

    private fun readArray(): JSONArray {
        val raw = prefs.getString(KEY_REQUESTS, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    companion object {
        const val PREFS_NAME = "warranty_requests_cache"
        const val KEY_REQUESTS = "requests"

        fun fromJson(item: JSONObject): SupportTicket {
            val statusRaw = item.optString("status").ifBlank { "new" }
            return SupportTicket(
                localId = item.optString("local_id"),
                serverId = item.optString("server_id"),
                statusRaw = statusRaw,
                status = SupportStatusMapper.toDomain(statusRaw),
                createdAt = item.optString("created_at"),
                updatedAt = item.optString("updated_at"),
                bmsUid = item.optString("bms_uid"),
                model = item.optString("model"),
                problem = item.optString("problem"),
                fio = item.optString("fio"),
                phone = item.optString("phone"),
                adminComment = item.optString("admin_comment"),
                listPageItem = item.optBoolean("list_page_item", false),
            )
        }

        fun toJson(ticket: SupportTicket): JSONObject {
            return JSONObject().apply {
                put("local_id", ticket.localId)
                put("server_id", ticket.serverId)
                put("status", ticket.statusRaw)
                put("created_at", ticket.createdAt)
                put("updated_at", ticket.updatedAt)
                put("bms_uid", ticket.bmsUid)
                put("model", ticket.model)
                put("problem", ticket.problem)
                put("fio", ticket.fio)
                put("phone", ticket.phone)
                put("admin_comment", ticket.adminComment)
                put("list_page_item", ticket.listPageItem)
            }
        }
    }
}
