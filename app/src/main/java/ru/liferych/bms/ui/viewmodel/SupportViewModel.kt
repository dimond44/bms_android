package ru.liferych.bms.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.liferych.bms.BuildConfig
import ru.liferych.bms.data.local.BmsIdentityStore
import ru.liferych.bms.data.local.SavedBatteriesStore
import ru.liferych.bms.data.support.SupportBatterySnapshotBuilder
import ru.liferych.bms.data.support.SupportMediaEncoder
import ru.liferych.bms.data.support.SupportStatusMapper
import ru.liferych.bms.domain.auth.AuthRepository
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.domain.repository.BmsRepository
import ru.liferych.bms.domain.support.SupportRepository
import ru.liferych.bms.domain.support.SupportResult
import ru.liferych.bms.domain.support.SupportSubmitDraft
import ru.liferych.bms.domain.support.SupportTicket
import ru.liferych.bms.ui.devicesearch.deviceUiDisplayName
import ru.liferych.bms.ui.screens.support.SupportAttachmentUi
import ru.liferych.bms.ui.screens.support.SupportRequestStatus
import ru.liferych.bms.ui.screens.support.SupportRequestUi
import ru.liferych.bms.ui.screens.support.SupportUiPhase

/**
 * ViewModel for Compose Support (warranty) flow.
 * Owns form/list phase; network via [SupportRepository].
 */
class SupportViewModel(
    private val supportRepository: SupportRepository,
    private val authRepository: AuthRepository,
    private val bmsRepository: BmsRepository,
    private val savedBatteriesStore: SavedBatteriesStore,
    private val bmsIdentityStore: BmsIdentityStore,
    private val mediaEncoder: SupportMediaEncoder,
) : ViewModel() {

    private val _phase = MutableStateFlow<SupportUiPhase>(SupportUiPhase.Home)
    val phase: StateFlow<SupportUiPhase> = _phase.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _attachments = MutableStateFlow<List<SupportAttachmentUi>>(emptyList())
    val attachments: StateFlow<List<SupportAttachmentUi>> = _attachments.asStateFlow()

    /** 1-based list page (server pagination, limit=5). */
    private var currentPage: Int = 1
    private var totalPages: Int = 1
    private var listLoadingGuard: Boolean = false

    val listLoading: StateFlow<Boolean> = supportRepository.listLoading

    val defaultFio: StateFlow<String> = authRepository.authState
        .combine(phase) { auth, _ -> profileFio(auth) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val defaultPhone: StateFlow<String> = authRepository.authState
        .combine(phase) { auth, _ -> profilePhone(auth) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Opens Home (contacts).
     */
    fun openHome() {
        _attachments.value = emptyList()
        _phase.value = SupportUiPhase.Home
    }

    /**
     * Opens new request form prefilled from Auth profile + battery model default.
     */
    fun openNewRequest() {
        _attachments.value = emptyList()
        _phase.value = SupportUiPhase.NewRequest(
            fio = profileFio(authRepository.authState.value),
            phone = profilePhone(authRepository.authState.value),
            model = supportModelDefault(),
            problem = "",
            consent = false,
            attachments = emptyList(),
        )
    }

    /**
     * Loads list for current BMS.
     * From Home/New → page 1. From Details back → keep [currentPage].
     */
    fun openRequestList() {
        val resetToFirst = _phase.value !is SupportUiPhase.RequestDetails
        loadRequests(page = if (resetToFirst) 1 else currentPage)
    }

    /**
     * Previous list page (server pagination).
     */
    fun goToPreviousPage() {
        if (listLoadingGuard || currentPage <= 1) return
        loadRequests(page = currentPage - 1)
    }

    /**
     * Next list page (server pagination).
     */
    fun goToNextPage() {
        if (listLoadingGuard || currentPage >= totalPages) return
        loadRequests(page = currentPage + 1)
    }

    /**
     * Server list fetch: page + limit=5 via repository/API.
     *
     * @param page 1-based page
     */
    private fun loadRequests(page: Int) {
        val uid = currentBmsUid()
        if (uid.isBlank() || uid == "unknown_bms") {
            _phase.value = SupportUiPhase.Error(
                "BMS не определена. Подключите батарею или откройте её из списка.",
            )
            return
        }
        if (listLoadingGuard) return
        listLoadingGuard = true
        val keep = (_phase.value as? SupportUiPhase.RequestList)?.requests.orEmpty()
        _phase.value = SupportUiPhase.RequestList(
            requests = keep,
            loading = true,
            total = (_phase.value as? SupportUiPhase.RequestList)?.total ?: 0,
            page = page.coerceAtLeast(1),
            totalPages = totalPages.coerceAtLeast(1),
        )
        viewModelScope.launch {
            when (val result = supportRepository.refreshTickets(uid, page = page.coerceAtLeast(1))) {
                is SupportResult.ListSuccess -> {
                    currentPage = result.page.page.coerceAtLeast(1)
                    totalPages = result.page.totalPages.coerceAtLeast(1)
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.RequestList(
                        requests = result.page.tickets.map { it.toUi() },
                        loading = false,
                        total = result.page.total,
                        page = currentPage,
                        totalPages = totalPages,
                    )
                }
                SupportResult.NetworkError -> {
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.Error(
                        "Не удалось связаться с сервером. Проверьте подключение к интернету.",
                    )
                }
                is SupportResult.Unauthorized -> {
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.Error(result.message)
                }
                is SupportResult.ServerError -> {
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.Error(result.message)
                }
                is SupportResult.ValidationError -> {
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.Error(result.message)
                }
                else -> {
                    listLoadingGuard = false
                    _phase.value = SupportUiPhase.Error("Не удалось обновить статусы обращений.")
                }
            }
        }
    }

    /**
     * Opens details/edit form for a ticket.
     *
     * @param request list card model
     */
    fun openDetails(request: SupportRequestUi) {
        _attachments.value = emptyList()
        _phase.value = SupportUiPhase.RequestDetails(
            request = request,
            fio = request.fio,
            phone = request.phone,
            model = request.model,
            problem = request.problem,
            consent = true,
            attachments = emptyList(),
            statusMessage = "Текущий статус: ${SupportStatusMapper.toRu(
                when (request.status) {
                    SupportRequestStatus.Open -> "new"
                    SupportRequestStatus.Closed -> "done"
                },
            )}",
        )
    }

    /**
     * Updates new-request form fields (keeps attachments from VM).
     */
    fun updateNewForm(form: SupportUiPhase.NewRequest) {
        _phase.value = form.copy(attachments = _attachments.value.map { it.displayName })
    }

    /**
     * Updates details form fields.
     */
    fun updateDetailsForm(form: SupportUiPhase.RequestDetails) {
        _phase.value = form.copy(attachments = _attachments.value.map { it.displayName })
    }

    /**
     * Adds media URI (max 5).
     *
     * @param uri content/file uri
     */
    fun addAttachment(uri: Uri) {
        val current = _attachments.value
        if (current.size >= SupportMediaEncoder.MAX_ATTACHMENTS) return
        if (current.any { it.uri == uri }) return
        val name = mediaEncoder.displayName(uri, current.size)
        val next = current + SupportAttachmentUi(uri = uri, displayName = name)
        _attachments.value = next
        syncAttachmentNamesToPhase()
    }

    /**
     * Removes attachment by index.
     *
     * @param index 0-based
     */
    fun removeAttachment(index: Int) {
        val list = _attachments.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _attachments.value = list
        syncAttachmentNamesToPhase()
    }

    /**
     * Submits new or details form. Double-submit blocked while [_submitting].
     */
    fun submitCurrent() {
        if (_submitting.value) return
        when (val current = _phase.value) {
            is SupportUiPhase.NewRequest -> submitForm(
                fio = current.fio,
                phone = current.phone,
                model = current.model,
                problem = current.problem,
                consent = current.consent,
                localId = "local_${System.currentTimeMillis()}",
                serverId = "",
                createdAt = "",
                onStatus = { msg -> _phase.value = current.copy(statusMessage = msg, attachments = _attachments.value.map { it.displayName }) },
            )
            is SupportUiPhase.RequestDetails -> submitForm(
                fio = current.fio,
                phone = current.phone,
                model = current.model,
                problem = current.problem,
                consent = current.consent,
                localId = current.request.localId,
                serverId = current.request.serverId.orEmpty(),
                createdAt = current.request.createdAt,
                onStatus = { msg -> _phase.value = current.copy(statusMessage = msg, attachments = _attachments.value.map { it.displayName }) },
            )
            else -> Unit
        }
    }

    private fun submitForm(
        fio: String,
        phone: String,
        model: String,
        problem: String,
        consent: Boolean,
        localId: String,
        serverId: String,
        createdAt: String,
        onStatus: (String) -> Unit,
    ) {
        if (!consent) {
            onStatus("Подтвердите согласие на обработку данных")
            return
        }
        if (_submitting.value) return
        _submitting.value = true
        onStatus("Отправка обращения...")
        viewModelScope.launch {
            val uid = currentBmsUid().ifBlank { "unknown_bms" }
            val address = resolveBluetoothAddress(uid)
            val bleName = resolveBluetoothName(address)
            val factorySn = bmsIdentityStore.displayFactorySerial(
                bmsIdentityStore.cachedFactorySerial(address),
            )
            val owner = (authRepository.authState.value as? AuthState.Authorized)?.profile
            val draft = SupportSubmitDraft(
                localId = localId,
                serverId = serverId,
                fio = fio.trim(),
                phone = phone.trim(),
                model = model.trim(),
                problem = problem.trim(),
                bmsUid = uid,
                bluetoothName = bleName.ifBlank { uid },
                bluetoothAddress = address,
                bmsSn = factorySn,
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = createdAt,
            )
            val snapshot = SupportBatterySnapshotBuilder.build(
                bmsUid = uid,
                bluetoothAddress = address,
                bluetoothName = bleName,
                battery = bmsRepository.batteryState.value,
                factorySerial = factorySn,
                owner = owner,
            )
            when (
                val result = supportRepository.submitTicket(
                    draft = draft,
                    mediaUris = _attachments.value.map { it.uri },
                    batterySnapshotJson = snapshot,
                )
            ) {
                is SupportResult.SubmitSuccess -> {
                    _submitting.value = false
                    _attachments.value = emptyList()
                    // Legacy: after success → list page 1.
                    currentPage = 1
                    loadRequests(page = 1)
                }
                is SupportResult.ValidationError -> {
                    _submitting.value = false
                    onStatus(result.message)
                }
                SupportResult.NetworkError -> {
                    _submitting.value = false
                    onStatus("Не удалось связаться с сервером. Проверьте подключение к интернету.")
                }
                is SupportResult.Unauthorized -> {
                    _submitting.value = false
                    onStatus(result.message)
                }
                is SupportResult.ServerError -> {
                    _submitting.value = false
                    onStatus(result.message)
                }
                else -> {
                    _submitting.value = false
                    onStatus("Не удалось отправить обращение. Попробуйте ещё раз.")
                }
            }
        }
    }

    private fun syncAttachmentNamesToPhase() {
        val names = _attachments.value.map { it.displayName }
        when (val p = _phase.value) {
            is SupportUiPhase.NewRequest -> _phase.value = p.copy(attachments = names)
            is SupportUiPhase.RequestDetails -> _phase.value = p.copy(attachments = names)
            else -> Unit
        }
    }

    private fun profileFio(auth: AuthState): String {
        return (auth as? AuthState.Authorized)?.profile?.fullName.orEmpty()
    }

    private fun profilePhone(auth: AuthState): String {
        val e164 = (auth as? AuthState.Authorized)?.profile?.phoneE164.orEmpty()
        return RuPhone.formatDisplay(e164)
    }

    /**
     * Legacy-compatible BMS uid (`DL-<MACdigits>` when address known).
     */
    private fun currentBmsUid(): String {
        val address = resolveBluetoothAddress("")
        if (address.isBlank()) return ""
        return SupportBatterySnapshotBuilder.resolveBmsUid(
            address = address,
            bluetoothName = resolveBluetoothName(address),
        )
    }

    /**
     * Connected MAC, else first saved battery MAC.
     *
     * @param preferredUid unused hint kept for call-site clarity
     */
    private fun resolveBluetoothAddress(preferredUid: String): String {
        val connected = bmsRepository.connectionState.value
        val live = when (connected) {
            is ru.liferych.bms.domain.model.BmsConnectionState.Connected -> connected.deviceAddress
            is ru.liferych.bms.domain.model.BmsConnectionState.Connecting -> connected.deviceAddress
            else -> null
        }
        if (!live.isNullOrBlank()) return live
        val saved = savedBatteriesStore.load()
        saved.firstOrNull { it.address.equals(preferredUid, true) }?.address?.let { return it }
        if (preferredUid.contains(':')) {
            saved.firstOrNull { preferredUid.equals(it.address, true) }?.address?.let { return it }
        }
        return saved.firstOrNull()?.address.orEmpty()
    }

    /**
     * Saved advertised BLE name for [address], if any.
     */
    private fun resolveBluetoothName(address: String): String {
        if (address.isBlank()) return ""
        return savedBatteriesStore.load()
            .firstOrNull { it.address.equals(address, true) }
            ?.bluetoothName
            .orEmpty()
            .trim()
    }

    private fun supportModelDefault(): String {
        val uid = currentBmsUid()
        val saved = savedBatteriesStore.load().firstOrNull { it.address.equals(uid, true) }
        val name = if (saved != null) {
            deviceUiDisplayName(saved.customName, saved.bluetoothName, uid)
        } else {
            uid
        }
        val battery = bmsRepository.batteryState.value
        val cells = battery.cellCount?.let { "${it}S" }.orEmpty()
        val capacity = battery.fullCapacityAh?.let { String.format(java.util.Locale.US, "%.1f Ah", it) }
            ?: battery.remainingCapacityAh?.let { String.format(java.util.Locale.US, "%.1f Ah", it) }
            ?: ""
        return listOf(name, cells, capacity)
            .filter { it.isNotBlank() && it != "unknown_bms" }
            .joinToString(" / ")
            .ifBlank { "LiFePO4 АКБ" }
    }

    private fun SupportTicket.toUi(): SupportRequestUi {
        return SupportRequestUi(
            localId = localId,
            serverId = serverId.ifBlank { null },
            status = when (status) {
                ru.liferych.bms.domain.support.SupportTicketStatus.Open -> SupportRequestStatus.Open
                ru.liferych.bms.domain.support.SupportTicketStatus.Closed -> SupportRequestStatus.Closed
            },
            createdAt = createdAt,
            bmsUid = bmsUid,
            model = model,
            problem = problem,
            fio = fio,
            phone = phone,
            adminComment = adminComment.ifBlank { null },
        )
    }

    class Factory(
        private val supportRepository: SupportRepository,
        private val authRepository: AuthRepository,
        private val bmsRepository: BmsRepository,
        private val savedBatteriesStore: SavedBatteriesStore,
        private val bmsIdentityStore: BmsIdentityStore,
        private val mediaEncoder: SupportMediaEncoder,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SupportViewModel::class.java)) {
                return SupportViewModel(
                    supportRepository,
                    authRepository,
                    bmsRepository,
                    savedBatteriesStore,
                    bmsIdentityStore,
                    mediaEncoder,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
