package ru.liferych.bms.domain.support

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * CLIENT warranty/support repository (legacy MainActivity support flow).
 */
interface SupportRepository {
    /** Cached tickets for current BMS (local + last page merge). */
    val tickets: StateFlow<List<SupportTicket>>

    /** List loading flag. */
    val listLoading: StateFlow<Boolean>

    /** Last list error message (empty when ok). */
    val listError: StateFlow<String>

    val listPage: StateFlow<Int>
    val listTotal: StateFlow<Int>
    val listTotalPages: StateFlow<Int>

    /**
     * Loads tickets for [bmsUid] from server and merges into local cache.
     *
     * @param bmsUid BLE identity (legacy bmsUid)
     * @param page 1-based page
     * @return SupportResult
     */
    suspend fun refreshTickets(bmsUid: String, page: Int = 1): SupportResult

    /**
     * Submits warranty request (create or update by server_id).
     *
     * @param draft form fields
     * @param mediaUris attachment URIs (max 5)
     * @param batterySnapshotJson optional telemetry JSON (legacy battery_snapshot)
     * @param configSnapshotJson optional config JSON
     * @return SupportResult
     */
    suspend fun submitTicket(
        draft: SupportSubmitDraft,
        mediaUris: List<Uri>,
        batterySnapshotJson: JSONObject?,
        configSnapshotJson: JSONObject? = null,
    ): SupportResult

    /**
     * Finds ticket by local id in cache.
     *
     * @param localId local_id
     * @return ticket or null
     */
    fun findByLocalId(localId: String): SupportTicket?

    /**
     * Tickets filtered for [bmsUid] for UI list (drafts + page items).
     *
     * @param bmsUid current BMS uid
     * @param page current page (drafts only on page 1)
     * @return ordered list
     */
    fun ticketsForUi(bmsUid: String, page: Int): List<SupportTicket>
}
