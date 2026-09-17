package ru.liferych.bms.data.support

import ru.liferych.bms.domain.support.SupportTicket
import ru.liferych.bms.domain.support.SupportTicketStatus

/**
 * Maps server / local warranty status strings to domain + RU labels.
 */
object SupportStatusMapper {
    /**
     * @param raw server/local status
     * @return Open unless done/closed
     */
    fun toDomain(raw: String): SupportTicketStatus {
        return if (isClosed(raw)) SupportTicketStatus.Closed else SupportTicketStatus.Open
    }

    /**
     * @param raw status string
     * @return true when closed
     */
    fun isClosed(raw: String): Boolean {
        val s = raw.trim()
        return s.equals("done", ignoreCase = true) || s.equals("closed", ignoreCase = true)
    }

    /**
     * Legacy UI labels: only Открыто / Закрыто.
     *
     * @param status domain status
     * @return RU label
     */
    fun toRu(status: SupportTicketStatus): String {
        return when (status) {
            SupportTicketStatus.Open -> "Открыто"
            SupportTicketStatus.Closed -> "Закрыто"
        }
    }

    /**
     * @param raw server status
     * @return RU label
     */
    fun toRu(raw: String): String = toRu(toDomain(raw))
}

/**
 * Maps remote list item JSON fields onto a [SupportTicket].
 *
 * @param serverId remote id
 * @param remoteStatus status
 * @param adminComment admin_comment
 * @param updatedAt updated_at
 * @param createdAt created_at
 * @param bmsUid bms_uid
 * @param model battery_model
 * @param problem problem_text
 * @param fio client_fio
 * @param phone client_phone
 * @param existing previous local ticket if any
 * @return domain ticket marked as list page item
 */
fun mapRemoteToTicket(
    serverId: String,
    remoteStatus: String,
    adminComment: String,
    updatedAt: String,
    createdAt: String,
    bmsUid: String,
    model: String,
    problem: String,
    fio: String,
    phone: String,
    existing: SupportTicket?,
): SupportTicket {
    val localId = existing?.localId?.ifBlank { null } ?: "server_$serverId"
    val statusRaw = remoteStatus.ifBlank { existing?.statusRaw ?: "new" }
    return SupportTicket(
        localId = localId,
        serverId = serverId,
        statusRaw = statusRaw,
        status = SupportStatusMapper.toDomain(statusRaw),
        createdAt = createdAt.ifBlank { existing?.createdAt.orEmpty() },
        updatedAt = updatedAt.ifBlank { existing?.updatedAt.orEmpty() },
        bmsUid = bmsUid.ifBlank { existing?.bmsUid.orEmpty() },
        model = model.ifBlank { existing?.model.orEmpty() },
        problem = problem.ifBlank { existing?.problem.orEmpty() },
        fio = fio.ifBlank { existing?.fio.orEmpty() },
        phone = phone.ifBlank { existing?.phone.orEmpty() },
        adminComment = adminComment.ifBlank { existing?.adminComment.orEmpty() },
        listPageItem = true,
    )
}
