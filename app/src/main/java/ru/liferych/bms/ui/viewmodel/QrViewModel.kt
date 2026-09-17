package ru.liferych.bms.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.liferych.bms.cellcode.CellCodeDecoder
import ru.liferych.bms.ui.screens.qr.QrUiPhase
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for CLIENT cell QR / Data Matrix check flow.
 *
 * Camera PreviewView stays in the UI layer; this VM owns phase, decode, and scan lock.
 * Does not touch BLE / Auth / Support.
 */
class QrViewModel : ViewModel() {

    private val _phase = MutableStateFlow<QrUiPhase>(QrUiPhase.Ready())
    val phase: StateFlow<QrUiPhase> = _phase.asStateFlow()

    /**
     * Duplicate-scan guard (legacy qrProcessing after successful raw read).
     * Reset on retry / leave scanner / manual path.
     */
    private val scanLocked = AtomicBoolean(false)

    /** True while scanner should ignore further raw callbacks. */
    fun isScanLocked(): Boolean = scanLocked.get()

    /**
     * Opens Ready with optional preserved input.
     *
     * @param codeInput manual field text
     */
    fun openReady(codeInput: String = currentInputHint()) {
        scanLocked.set(false)
        _phase.value = QrUiPhase.Ready(codeInput = codeInput, inlineError = null)
    }

    /**
     * Updates manual code field.
     *
     * @param codeInput typed value
     */
    fun onCodeChange(codeInput: String) {
        val current = _phase.value
        if (current is QrUiPhase.Ready) {
            _phase.value = current.copy(codeInput = codeInput, inlineError = null)
        }
    }

    /**
     * Decodes manual input via [CellCodeDecoder] (same path as camera).
     */
    fun checkManual() {
        val current = _phase.value as? QrUiPhase.Ready ?: return
        val raw = current.codeInput
        if (raw.isBlank()) {
            _phase.value = current.copy(inlineError = "Введите или отсканируйте код")
            return
        }
        scanLocked.set(true)
        _phase.value = QrUiPhase.Result(CellCodeDecoder.decode(raw))
    }

    /**
     * Enter Scanning after CAMERA permission is granted.
     */
    fun openScanning() {
        scanLocked.set(false)
        _phase.value = QrUiPhase.Scanning
    }

    /**
     * Permission missing / denied.
     */
    fun openPermissionRequired() {
        scanLocked.set(false)
        _phase.value = QrUiPhase.PermissionRequired
    }

    /**
     * Camera bind / preview failure (not cell-code decode failure).
     *
     * @param message user-safe text
     */
    fun openCameraError(message: String = "Не удалось открыть камеру") {
        scanLocked.set(false)
        _phase.value = QrUiPhase.Error(message)
    }

    /**
     * Raw barcode from analyzer. Locked after first accepted value.
     * Always routes through [CellCodeDecoder] — FAILED recognition → Result UI (legacy).
     *
     * @param rawCode scanner rawValue unmodified
     */
    fun onRawScanned(rawCode: String) {
        if (rawCode.isBlank()) return
        if (_phase.value !is QrUiPhase.Scanning) return
        if (!scanLocked.compareAndSet(false, true)) return
        // Decoder owns normalization; do not preprocess here.
        _phase.value = QrUiPhase.Result(CellCodeDecoder.decode(rawCode))
    }

    /**
     * Transient ML Kit failure while still on Scanning — unlock only.
     */
    fun onScannerFrameFailure() {
        if (_phase.value is QrUiPhase.Scanning) {
            scanLocked.set(false)
        }
    }

    /**
     * Scan again from Result.
     */
    fun scanAgain() {
        scanLocked.set(false)
        _phase.value = QrUiPhase.Scanning
    }

    /**
     * Back from Result to Ready with normalized code filled.
     */
    fun backFromResult() {
        val decoded = (_phase.value as? QrUiPhase.Result)?.decoded
        openReady(decoded?.normalizedCode.orEmpty())
    }

    /**
     * Manual entry from Result / Scanner.
     */
    fun manualFromResult() {
        val decoded = (_phase.value as? QrUiPhase.Result)?.decoded
        openReady(decoded?.normalizedCode.orEmpty())
    }

    /**
     * Retry from Error → Ready.
     */
    fun retryFromError() {
        openReady()
    }

    private fun currentInputHint(): String {
        return when (val p = _phase.value) {
            is QrUiPhase.Ready -> p.codeInput
            is QrUiPhase.Result -> p.decoded.normalizedCode
            else -> ""
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(QrViewModel::class.java)) {
                return QrViewModel() as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
