package ru.liferych.bms.domain.support

/**
 * Server warranty status values mapped for CLIENT UI.
 * Legacy shows only «Открыто» / «Закрыто» (done/closed → closed).
 */
enum class SupportTicketStatus {
    Open,
    Closed,
}

/**
 * Domain support ticket (warranty request).
 * Fields mirror legacy local cache + list API mapping.
 */
data class SupportTicket(
    val localId: String,
    val serverId: String = "",
    val statusRaw: String = "new",
    val status: SupportTicketStatus = SupportTicketStatus.Open,
    val createdAt: String = "",
    val updatedAt: String = "",
    val bmsUid: String = "",
    val model: String = "",
    val problem: String = "",
    val fio: String = "",
    val phone: String = "",
    val adminComment: String = "",
    /** True when item belongs to the last fetched list page. */
    val listPageItem: Boolean = false,
)

/**
 * Draft for create/update submit (legacy form fields).
 */
data class SupportSubmitDraft(
    val localId: String,
    val serverId: String = "",
    val fio: String,
    val phone: String,
    val model: String,
    val problem: String,
    val bmsUid: String,
    val bluetoothName: String = "",
    val bluetoothAddress: String = "",
    val bmsSn: String = "",
    val appVersion: String,
    val createdAt: String = "",
)

/**
 * Result of list fetch.
 */
data class SupportTicketPage(
    val tickets: List<SupportTicket>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

/**
 * Submit / list operation result (no secrets).
 */
sealed interface SupportResult {
    data class SubmitSuccess(val ticket: SupportTicket) : SupportResult
    data class ListSuccess(val page: SupportTicketPage) : SupportResult
    data class ValidationError(val message: String) : SupportResult
    data object NetworkError : SupportResult
    data class ServerError(val message: String, val httpCode: Int = 0) : SupportResult
    data class Unauthorized(val message: String) : SupportResult
}
