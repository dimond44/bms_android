package ru.liferych.bms.data.scanner

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning

/**
 * Shared ML Kit barcode scanner client for cell QR / Data Matrix.
 * One instance per process; close only on app teardown if needed.
 *
 * Does not own CameraX lifecycle.
 */
object MlKitBarcodeScannerClient {
    @Volatile
    private var client: BarcodeScanner? = null

    /**
     * @return process-wide scanner restricted to [CellBarcodeFormats]
     */
    fun get(): BarcodeScanner {
        client?.let { return it }
        return synchronized(this) {
            client ?: BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        CellBarcodeFormats.mlKitFormats[0],
                        *CellBarcodeFormats.mlKitFormats.drop(1).toIntArray(),
                    )
                    .build(),
            ).also { client = it }
        }
    }
}
