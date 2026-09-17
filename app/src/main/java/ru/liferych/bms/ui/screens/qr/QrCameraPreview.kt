package ru.liferych.bms.ui.screens.qr

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.liferych.bms.data.scanner.CameraBarcodeAnalyzer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live CameraX preview + QR/Data Matrix analysis for [QrScanningShell].
 *
 * Binds once per composition; unbinds on dispose (leave tab / leave Scanning).
 *
 * @param onRawCode first non-blank barcode rawValue
 * @param onBindError camera provider / bind failure
 * @param modifier layout modifier for PreviewView host
 */
@Composable
fun QrCameraPreview(
    onRawCode: (String) -> Unit,
    onBindError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val bound = remember { AtomicBoolean(false) }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analyzer = remember {
        CameraBarcodeAnalyzer(onRawCode = onRawCode)
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    bound.set(true)
                } catch (e: Exception) {
                    Log.w("QrCameraPreview", "bind failed: ${e.javaClass.simpleName}")
                    onBindError("Не удалось открыть камеру")
                }
            },
            mainExecutor,
        )

        onDispose {
            try {
                if (bound.get()) {
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener(
                        {
                            try {
                                future.get().unbindAll()
                            } catch (_: Exception) {
                            }
                        },
                        mainExecutor,
                    )
                }
            } catch (_: Exception) {
            }
            analysisExecutor.shutdown()
            bound.set(false)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
