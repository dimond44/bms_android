package ru.liferych.bms.ui.screens.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.liferych.bms.R
import ru.liferych.bms.cellcode.CellCodeDecoder
import ru.liferych.bms.cellcode.CellCodeRecognition
import ru.liferych.bms.cellcode.CellQrDecodeResult
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.viewmodel.QrViewModel

/**
 * UI phase for CLIENT QR flow (legacy showQrInput / showQrScanner / showQrResult).
 */
sealed class QrUiPhase {
    data class Ready(val codeInput: String = "", val inlineError: String? = null) : QrUiPhase()
    data object Scanning : QrUiPhase()
    data class Result(val decoded: CellQrDecodeResult) : QrUiPhase()
    data class Error(val message: String) : QrUiPhase()
    data object PermissionRequired : QrUiPhase()
}

/**
 * CLIENT QR screen — cell/element code check via CameraX + ML Kit + [CellCodeDecoder].
 *
 * @param viewModel QR state / decode (null only for Compose Preview)
 * @param onBack leave QR tab
 * @param initialPhase Preview-only override when [viewModel] is null
 * @param modifier layout modifier
 */
@Composable
fun QrScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrViewModel? = null,
    initialPhase: QrUiPhase = QrUiPhase.Ready(),
) {
    if (viewModel == null) {
        QrScreenPreviewHost(onBack = onBack, initialPhase = initialPhase, modifier = modifier)
        return
    }

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.openScanning()
        } else {
            viewModel.openPermissionRequired()
        }
    }

    fun requestOrOpenScanner() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.openScanning()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (val current = phase) {
        is QrUiPhase.Ready -> QrReadyContent(
            codeInput = current.codeInput,
            inlineError = current.inlineError,
            onBack = onBack,
            onCodeChange = { viewModel.onCodeChange(it) },
            onOpenScanner = { requestOrOpenScanner() },
            onCheck = { viewModel.checkManual() },
            modifier = modifier,
        )

        QrUiPhase.Scanning -> QrScanningShell(
            onBack = { viewModel.openReady() },
            onManualEntry = { viewModel.openReady() },
            onRawCode = { viewModel.onRawScanned(it) },
            onBindError = { viewModel.openCameraError(it) },
            enableCamera = true,
            modifier = modifier,
        )

        is QrUiPhase.Result -> QrResultContent(
            decoded = current.decoded,
            onBack = { viewModel.backFromResult() },
            onScanAgain = { requestOrOpenScanner() },
            onManualEntry = { viewModel.manualFromResult() },
            modifier = modifier,
        )

        is QrUiPhase.Error -> QrErrorContent(
            message = current.message,
            onBack = onBack,
            onRetry = { viewModel.retryFromError() },
            modifier = modifier,
        )

        QrUiPhase.PermissionRequired -> QrPermissionContent(
            onBack = { viewModel.openReady() },
            onRetry = { requestOrOpenScanner() },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            },
            modifier = modifier,
        )
    }
}

/**
 * Preview / screenshot host without CameraX or ViewModel.
 */
@Composable
private fun QrScreenPreviewHost(
    onBack: () -> Unit,
    initialPhase: QrUiPhase,
    modifier: Modifier = Modifier,
) {
    var phase by remember {
        mutableStateOf(initialPhase)
    }
    when (val current = phase) {
        is QrUiPhase.Ready -> QrReadyContent(
            codeInput = current.codeInput,
            inlineError = current.inlineError,
            onBack = onBack,
            onCodeChange = { phase = QrUiPhase.Ready(codeInput = it) },
            onOpenScanner = { phase = QrUiPhase.Scanning },
            onCheck = {
                if (current.codeInput.isBlank()) {
                    phase = current.copy(inlineError = "Введите или отсканируйте код")
                } else {
                    phase = QrUiPhase.Result(CellCodeDecoder.decode(current.codeInput))
                }
            },
            modifier = modifier,
        )
        QrUiPhase.Scanning -> QrScanningShell(
            onBack = { phase = QrUiPhase.Ready() },
            onManualEntry = { phase = QrUiPhase.Ready() },
            enableCamera = false,
            modifier = modifier,
        )
        is QrUiPhase.Result -> QrResultContent(
            decoded = current.decoded,
            onBack = { phase = QrUiPhase.Ready(current.decoded.normalizedCode) },
            onScanAgain = { phase = QrUiPhase.Scanning },
            onManualEntry = { phase = QrUiPhase.Ready(current.decoded.normalizedCode) },
            modifier = modifier,
        )
        is QrUiPhase.Error -> QrErrorContent(
            message = current.message,
            onBack = onBack,
            onRetry = { phase = QrUiPhase.Ready() },
            modifier = modifier,
        )
        QrUiPhase.PermissionRequired -> QrPermissionContent(
            onBack = { phase = QrUiPhase.Ready() },
            onRetry = { phase = QrUiPhase.Scanning },
            onOpenSettings = {},
            modifier = modifier,
        )
    }
}

@Composable
private fun QrReadyContent(
    codeInput: String,
    inlineError: String?,
    onBack: () -> Unit,
    onCodeChange: (String) -> Unit,
    onOpenScanner: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Проверка QR-кода", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LiferychDimens.ScreenPadding)
                .padding(bottom = 14.dp),
        ) {
            Text(
                text = "Проверить QR-код",
                color = LiferychColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            val hintShape = RoundedCornerShape(18.dp)
            Text(
                text = "Наведите камеру на QR/Data Matrix код элемента или введите код вручную.",
                color = LiferychColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(hintShape)
                    .background(Color(0xFFF6F7F9))
                    .border(1.dp, LiferychColors.Border, hintShape)
                    .padding(14.dp),
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Код элемента",
                color = LiferychColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val scanShape = RoundedCornerShape(18.dp)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(scanShape)
                        .background(LiferychColors.BrandYellowSoft)
                        .border(1.dp, LiferychColors.BrandYellowDark, scanShape)
                        .clickable(onClick = onOpenScanner),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_liferych_qr),
                        contentDescription = "Сканировать",
                        tint = LiferychColors.BrandYellowDark,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                val fieldShape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(fieldShape)
                        .background(LiferychColors.Surface)
                        .border(1.dp, LiferychColors.Border, fieldShape)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (codeInput.isEmpty()) {
                        Text(
                            text = "Ввести код вручную",
                            color = LiferychColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = codeInput,
                        onValueChange = onCodeChange,
                        textStyle = TextStyle(
                            color = LiferychColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        cursorBrush = SolidColor(LiferychColors.BrandYellowDark),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Допускается код из двух частей — пробелы и переносы будут убраны.",
                color = LiferychColors.TextSecondary,
                fontSize = 12.sp,
            )

            if (inlineError != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = inlineError,
                    color = LiferychColors.Error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))
            val btnShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(btnShape)
                    .background(LiferychColors.BrandYellow)
                    .clickable(onClick = onCheck),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "ПРОВЕРИТЬ",
                    color = LiferychColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Открыть сканер камеры",
                color = LiferychColors.BrandYellowDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenScanner)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/**
 * Camera scanner UI — live CameraX preview when [enableCamera], shell otherwise (Preview).
 */
@Composable
private fun QrScanningShell(
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    enableCamera: Boolean = false,
    onRawCode: (String) -> Unit = {},
    onBindError: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Сканер QR-кода", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = LiferychDimens.ScreenPadding)
                .padding(bottom = 14.dp),
        ) {
            Text(
                text = "Сканирование кода",
                color = LiferychColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            val frameShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(frameShape)
                    .background(Color(0xFFF6F7F9))
                    .border(1.dp, LiferychColors.Border, frameShape),
            ) {
                if (enableCamera) {
                    QrCameraPreview(
                        onRawCode = onRawCode,
                        onBindError = onBindError,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            tint = LiferychColors.IconMuted,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Камера (preview)",
                            color = LiferychColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // Corner frame marks (legacy yellow corners)
                Text(
                    text = "⌜                 ⌝\n\n\n\n\n⌞                 ⌟",
                    color = LiferychColors.BrandYellow,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                val tipShape = RoundedCornerShape(16.dp)
                Text(
                    text = "Наведите камеру на QR-код или Data Matrix",
                    color = LiferychColors.TextPrimary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 22.dp, vertical = 22.dp)
                        .fillMaxWidth()
                        .clip(tipShape)
                        .background(Color(0xE6FFFFFF))
                        .border(1.dp, LiferychColors.Border, tipShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Ввести код вручную",
                color = LiferychColors.BrandYellowDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManualEntry)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun QrResultContent(
    decoded: CellQrDecodeResult,
    onBack: () -> Unit,
    onScanAgain: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (statusText, statusColor, statusBg) = when (decoded.recognition) {
        CellCodeRecognition.FULL -> Triple(
            "Код распознан",
            Color(0xFF1FB35A),
            Color(0xFFEDFAF2),
        )
        CellCodeRecognition.PARTIAL -> Triple(
            "Код распознан частично",
            Color(0xFFE09600),
            Color(0xFFFFF8DA),
        )
        CellCodeRecognition.FAILED -> Triple(
            "Не удалось распознать код элемента",
            Color(0xFF6F7781),
            Color(0xFFF1F3F5),
        )
    }

    fun displayOrDash(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "Не определено"
    fun capacityText(): String = decoded.nominalCapacityAh?.let { "%.0f Ач".format(it) }
        ?: "Не определено"
    fun voltageText(): String = decoded.nominalVoltageV?.let { "%.1f В".format(it) }
        ?: "Не определено"

    val rows = buildList {
        add("Производитель" to displayOrDash(decoded.manufacturer))
        add("Тип продукта" to displayOrDash(decoded.productType))
        add("Тип аккумулятора" to displayOrDash(decoded.batteryType))
        add("Номинальная ёмкость" to capacityText())
        add("Номинальное напряжение" to voltageText())
        add("Дата производства" to displayOrDash(decoded.productionDateDisplay))
        add("Серия" to displayOrDash(decoded.productSeries))
        decoded.productAttribute?.let { add("Атрибут" to it) }
        decoded.subsidiary?.let { add("Дочерняя компания" to it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Проверка элемента", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LiferychDimens.ScreenPadding)
                .padding(bottom = 14.dp),
        ) {
            Text(
                text = "Проверка элемента",
                color = LiferychColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            val statusShape = RoundedCornerShape(16.dp)
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(statusShape)
                    .background(statusBg)
                    .border(1.dp, statusColor, statusShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Код элемента",
                color = LiferychColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            val codeShape = RoundedCornerShape(14.dp)
            Text(
                text = decoded.normalizedCode.ifBlank { decoded.rawCode },
                color = LiferychColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(codeShape)
                    .background(Color(0xFFF6F7F9))
                    .border(1.dp, LiferychColors.Border, codeShape)
                    .padding(14.dp),
            )

            Spacer(Modifier.height(12.dp))
            val reportShape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(reportShape)
                    .background(LiferychColors.Surface)
                    .border(1.dp, LiferychColors.Border, reportShape),
            ) {
                rows.forEachIndexed { index, (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index % 2 == 0) Color(0xFFF6F7F9) else Color.Transparent,
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            color = LiferychColors.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value,
                            color = LiferychColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val btnShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(btnShape)
                    .background(LiferychColors.BrandYellow)
                    .clickable(onClick = onScanAgain),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "СКАНИРОВАТЬ ЕЩЁ",
                    color = LiferychColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(
                text = "Ввести код вручную",
                color = LiferychColors.BrandYellowDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManualEntry)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun QrErrorContent(
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Проверка QR-кода", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(LiferychDimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_liferych_error),
                contentDescription = null,
                tint = LiferychColors.Error,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = LiferychColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Повторить",
                color = LiferychColors.BrandYellowDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}

@Composable
private fun QrPermissionContent(
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Сканер QR-кода", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(LiferychDimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = LiferychColors.IconMuted,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Нужен доступ к камере",
                color = LiferychColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Разрешите доступ к камере в настройках, чтобы сканировать QR/Data Matrix.",
                color = LiferychColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Повторить",
                color = LiferychColors.BrandYellowDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Открыть настройки",
                color = LiferychColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    }
}

@Preview(showBackground = true, name = "QrReadyPreview", heightDp = 860)
@Composable
private fun QrReadyPreview() {
    LiferychTheme {
        QrScreen(onBack = {}, initialPhase = QrUiPhase.Ready())
    }
}

@Preview(showBackground = true, name = "QrScanningPreview", heightDp = 860)
@Composable
private fun QrScanningPreview() {
    LiferychTheme {
        QrScreen(onBack = {}, initialPhase = QrUiPhase.Scanning)
    }
}

@Preview(showBackground = true, name = "QrResultPreview", heightDp = 900)
@Composable
private fun QrResultPreview() {
    LiferychTheme {
        QrScreen(
            onBack = {},
            initialPhase = QrUiPhase.Result(
                CellCodeDecoder.decode("04qcb76836300jbc40000903"),
            ),
        )
    }
}

@Preview(showBackground = true, name = "QrPermissionPreview", heightDp = 800)
@Composable
private fun QrPermissionPreview() {
    LiferychTheme {
        QrScreen(onBack = {}, initialPhase = QrUiPhase.PermissionRequired)
    }
}
