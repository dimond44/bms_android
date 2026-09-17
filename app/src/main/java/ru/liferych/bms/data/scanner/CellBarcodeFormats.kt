package ru.liferych.bms.data.scanner

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Barcode formats allowed for CLIENT cell-code scanner.
 * Matches legacy MainActivity BarcodeScannerOptions (QR + Data Matrix only).
 */
object CellBarcodeFormats {
    val mlKitFormats: IntArray = intArrayOf(
        Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_DATA_MATRIX,
    )
}
