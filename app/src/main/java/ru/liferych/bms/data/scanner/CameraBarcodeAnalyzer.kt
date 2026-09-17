package ru.liferych.bms.data.scanner

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX [ImageAnalysis.Analyzer] for cell QR / Data Matrix.
 *
 * Emits the first non-blank [Barcode.rawValue] then locks until [unlock].
 * Always closes [ImageProxy]. Runs ML Kit off the main thread via CameraX executor.
 *
 * @param onRawCode callback with raw scanner string (no preprocessing)
 * @param onScannerFailure transient ML Kit failure (proxy already closed)
 */
class CameraBarcodeAnalyzer(
    private val onRawCode: (String) -> Unit,
    private val onScannerFailure: (() -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val locked = AtomicBoolean(false)
    private val scanner = MlKitBarcodeScannerClient.get()

    /**
     * Blocks further successful emissions until [unlock].
     * Called after a valid raw code is delivered.
     */
    fun lock() {
        locked.set(true)
    }

    /**
     * Allows the next successful barcode emission (retry / re-enter scanner).
     */
    fun unlock() {
        locked.set(false)
    }

    /** @return true when duplicate frames are ignored */
    fun isLocked(): Boolean = locked.get()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (locked.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        // Soft lock while a frame is in-flight (legacy qrProcessing=true before process).
        if (!locked.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { barcode ->
                    barcode.rawValue?.takeIf { it.isNotBlank() }
                }
                if (value != null) {
                    // Keep locked (legacy: leave qrProcessing=true after success).
                    onRawCode(value)
                } else {
                    locked.set(false)
                }
            }
            .addOnFailureListener { e ->
                locked.set(false)
                Log.w(LOG_TAG, "barcode process failed: ${e.javaClass.simpleName}")
                onScannerFailure?.invoke()
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    companion object {
        private const val LOG_TAG = "CameraBarcodeAnalyzer"
    }
}
