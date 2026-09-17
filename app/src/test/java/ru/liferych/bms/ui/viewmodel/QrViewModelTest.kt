package ru.liferych.bms.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.liferych.bms.cellcode.CellCodeRecognition
import ru.liferych.bms.ui.screens.qr.QrUiPhase

/**
 * Glue tests: raw scan / manual → CellCodeDecoder → UI phase; debounce lock.
 */
class QrViewModelTest {

    private lateinit var vm: QrViewModel

    @Before
    fun setUp() {
        vm = QrViewModel()
    }

    @Test
    fun rawScan_success_goesToResult() {
        vm.openScanning()
        vm.onRawScanned("04qcb76836300jbc40000903")
        val phase = vm.phase.value
        assertTrue(phase is QrUiPhase.Result)
        val decoded = (phase as QrUiPhase.Result).decoded
        assertTrue(
            decoded.recognition == CellCodeRecognition.FULL ||
                decoded.recognition == CellCodeRecognition.PARTIAL,
        )
        assertTrue(vm.isScanLocked())
    }

    @Test
    fun rawScan_invalid_stillResultWithFailedRecognition() {
        vm.openScanning()
        vm.onRawScanned("not-a-valid-cell-code")
        val phase = vm.phase.value
        assertTrue(phase is QrUiPhase.Result)
        assertEquals(
            CellCodeRecognition.FAILED,
            (phase as QrUiPhase.Result).decoded.recognition,
        )
    }

    @Test
    fun duplicateScan_ignoredWhileLocked() {
        vm.openScanning()
        vm.onRawScanned("04qcb76836300jbc40000903")
        val first = (vm.phase.value as QrUiPhase.Result).decoded.rawCode
        vm.onRawScanned("001CB0000000000000000001")
        val second = (vm.phase.value as QrUiPhase.Result).decoded.rawCode
        assertEquals(first, second)
    }

    @Test
    fun retry_unlocksAndOpensScanning() {
        vm.openScanning()
        vm.onRawScanned("04qcb76836300jbc40000903")
        assertTrue(vm.isScanLocked())
        vm.scanAgain()
        assertFalse(vm.isScanLocked())
        assertTrue(vm.phase.value is QrUiPhase.Scanning)
    }

    @Test
    fun manualInput_usesSameDecoderPath() {
        vm.openReady("04qcb76836300jbc40000903")
        vm.checkManual()
        val phase = vm.phase.value
        assertTrue(phase is QrUiPhase.Result)
        assertTrue((phase as QrUiPhase.Result).decoded.normalizedCode.isNotBlank())
    }

    @Test
    fun manualBlank_showsInlineError() {
        vm.openReady("")
        vm.checkManual()
        val phase = vm.phase.value
        assertTrue(phase is QrUiPhase.Ready)
        assertEquals("Введите или отсканируйте код", (phase as QrUiPhase.Ready).inlineError)
    }

    @Test
    fun rawScan_ignoredWhenNotScanning() {
        vm.openReady()
        vm.onRawScanned("04qcb76836300jbc40000903")
        assertTrue(vm.phase.value is QrUiPhase.Ready)
        assertFalse(vm.isScanLocked())
    }
}
