package ru.liferych.bms.ui.screens.support

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme

/**
 * Attachment reference for Support form (URI + display name). No Bitmap in state.
 */
data class SupportAttachmentUi(
    val uri: android.net.Uri,
    val displayName: String,
)

/** Client-facing warranty status labels (legacy: Открыто / Закрыто). */
enum class SupportRequestStatus {
    Open,
    Closed,
}

/**
 * UI model for a warranty support request card / details.
 */
data class SupportRequestUi(
    val localId: String,
    val serverId: String?,
    val status: SupportRequestStatus,
    val createdAt: String,
    val bmsUid: String,
    val model: String,
    val problem: String,
    val fio: String,
    val phone: String,
    val adminComment: String? = null,
    val attachmentNames: List<String> = emptyList(),
)

/**
 * CLIENT Support phases — mirror legacy supportMode home/new/list/edit + error.
 */
sealed class SupportUiPhase {
    data object Home : SupportUiPhase()
    data class NewRequest(
        val fio: String = "",
        val phone: String = "",
        val model: String = "LiFePO4 АКБ",
        val problem: String = "",
        val consent: Boolean = false,
        val attachments: List<String> = emptyList(),
        val statusMessage: String = "",
    ) : SupportUiPhase()

    data class RequestList(
        val requests: List<SupportRequestUi>,
        val loading: Boolean = false,
        val total: Int = 0,
        val page: Int = 1,
        val totalPages: Int = 1,
    ) : SupportUiPhase()

    data class RequestDetails(
        val request: SupportRequestUi,
        val fio: String,
        val phone: String,
        val model: String,
        val problem: String,
        val consent: Boolean = true,
        val attachments: List<String> = emptyList(),
        val statusMessage: String = "",
    ) : SupportUiPhase()

    data class Error(val message: String) : SupportUiPhase()
}

/**
 * CLIENT Support screen — contacts / warranty form / request list.
 *
 * @param phase current UI phase from SupportViewModel
 * @param attachments real URI attachments (display names mirrored in phase)
 * @param submitting true while create/update request runs
 * @param onBack leave support tab
 * @param onOpenHome segment → home
 * @param onOpenNew segment → new form
 * @param onOpenList segment → list + refresh
 * @param onOpenDetails open ticket details
 * @param onNewFormChange edit new form
 * @param onDetailsFormChange edit details form
 * @param onSubmit send current form
 * @param onAddAttachment add URI from camera/gallery
 * @param onRemoveAttachment remove by index
 * @param onDiagnostics diagnostics action
 * @param modifier layout modifier
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    phase: SupportUiPhase = SupportUiPhase.Home,
    attachments: List<SupportAttachmentUi> = emptyList(),
    submitting: Boolean = false,
    onOpenHome: () -> Unit = {},
    onOpenNew: () -> Unit = {},
    onOpenList: () -> Unit = {},
    onOpenDetails: (SupportRequestUi) -> Unit = {},
    onNewFormChange: (SupportUiPhase.NewRequest) -> Unit = {},
    onDetailsFormChange: (SupportUiPhase.RequestDetails) -> Unit = {},
    onSubmit: () -> Unit = {},
    onAddAttachment: (android.net.Uri) -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    /** Preview-only seed; ignored when [phase] provided by VM. */
    initialPhase: SupportUiPhase? = null,
    defaultFio: String = "",
    defaultPhone: String = "",
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var previewPhase by remember { mutableStateOf(initialPhase ?: phase) }
    val currentPhase = if (initialPhase != null) previewPhase else phase
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val takePictureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val pending = pendingCameraUri
        pendingCameraUri = null
        if (ok && pending != null) onAddAttachment(pending)
    }

    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.take(ru.liferych.bms.data.support.SupportMediaEncoder.MAX_ATTACHMENTS).forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            onAddAttachment(uri)
        }
    }

    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchSupportCamera(context) { uri ->
                pendingCameraUri = uri
                takePictureLauncher.launch(uri)
            }
        }
    }

    fun onTakePhoto() {
        if (attachments.size >= ru.liferych.bms.data.support.SupportMediaEncoder.MAX_ATTACHMENTS) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchSupportCamera(context) { uri ->
                pendingCameraUri = uri
                takePictureLauncher.launch(uri)
            }
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    fun onPickGallery() {
        galleryLauncher.launch(arrayOf("image/*", "video/*"))
    }

    fun onSegment(segment: SupportSegment) {
        if (initialPhase != null) {
            previewPhase = segmentToPhase(segment, defaultFio, defaultPhone)
            return
        }
        when (segment) {
            SupportSegment.None -> onOpenHome()
            SupportSegment.New -> onOpenNew()
            SupportSegment.List -> onOpenList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(
            title = "Поддержка",
            onBack = {
                when (currentPhase) {
                    is SupportUiPhase.RequestDetails -> onOpenList()
                    is SupportUiPhase.NewRequest,
                    is SupportUiPhase.RequestList,
                    is SupportUiPhase.Error,
                    -> onOpenHome()
                    SupportUiPhase.Home -> onBack()
                }
            },
        )

        when (val current = currentPhase) {
            SupportUiPhase.Home -> SupportHomeBody(
                segment = SupportSegment.None,
                onSegment = { onSegment(it) },
                onDiagnostics = {
                    if (initialPhase != null) {
                        previewPhase = SupportUiPhase.Error(
                            "Диагностика BMS будет перенесена на следующем этапе",
                        )
                    } else {
                        onDiagnostics()
                    }
                },
                onDial = { dialPhone(context, "+79320781011") },
                onOpenUrl = { openUrl(context, it) },
            )

            is SupportUiPhase.NewRequest -> SupportNewBody(
                form = current,
                submitting = submitting,
                attachmentRows = attachments,
                onSegment = { onSegment(it) },
                onFormChange = { form ->
                    if (initialPhase != null) previewPhase = form else onNewFormChange(form)
                },
                onSubmit = {
                    if (initialPhase != null) {
                        previewPhase = current.copy(statusMessage = "Preview: API не вызван")
                    } else {
                        onSubmit()
                    }
                },
                onTakePhoto = { onTakePhoto() },
                onPickGallery = { onPickGallery() },
                onRemoveAttachment = onRemoveAttachment,
            )

            is SupportUiPhase.RequestList -> {
                when {
                    current.loading && current.requests.isEmpty() -> SupportLoadingBody(
                        onSegment = { onSegment(it) },
                    )
                    !current.loading && current.requests.isEmpty() -> SupportEmptyBody(
                        onSegment = { onSegment(it) },
                    )
                    else -> SupportListBody(
                        requests = current.requests,
                        total = current.total,
                        page = current.page,
                        totalPages = current.totalPages,
                        loading = current.loading,
                        onSegment = { onSegment(it) },
                        onOpen = { req ->
                            if (initialPhase != null) {
                                previewPhase = SupportUiPhase.RequestDetails(
                                    request = req,
                                    fio = req.fio,
                                    phone = req.phone,
                                    model = req.model,
                                    problem = req.problem,
                                    attachments = req.attachmentNames,
                                    statusMessage = "Текущий статус: ${statusRu(req.status)}",
                                )
                            } else {
                                onOpenDetails(req)
                            }
                        },
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                    )
                }
            }

            is SupportUiPhase.RequestDetails -> SupportDetailsBody(
                phase = current,
                submitting = submitting,
                attachmentRows = attachments,
                onSegment = { onSegment(it) },
                onFormChange = { form ->
                    if (initialPhase != null) previewPhase = form else onDetailsFormChange(form)
                },
                onSubmit = {
                    if (initialPhase != null) {
                        previewPhase = current.copy(statusMessage = "Preview: API не вызван")
                    } else {
                        onSubmit()
                    }
                },
                onTakePhoto = { onTakePhoto() },
                onPickGallery = { onPickGallery() },
                onRemoveAttachment = onRemoveAttachment,
            )

            is SupportUiPhase.Error -> SupportErrorBody(
                message = current.message,
                onBackHome = {
                    if (initialPhase != null) previewPhase = SupportUiPhase.Home else onOpenHome()
                },
            )
        }
    }
}

private fun launchSupportCamera(
    context: android.content.Context,
    onUriReady: (android.net.Uri) -> Unit,
) {
    try {
        val dir = java.io.File(context.cacheDir, "warranty_photos").apply { mkdirs() }
        val fileName = "Фото_${java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())}.jpg"
        val photoFile = java.io.File(dir, fileName)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile,
        )
        onUriReady(uri)
    } catch (_: Exception) {
    }
}

private fun dialPhone(context: android.content.Context, e164: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$e164")),
        )
    } catch (_: Exception) {
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    } catch (_: Exception) {
    }
}

private enum class SupportSegment { None, New, List }

private fun segmentToPhase(
    segment: SupportSegment,
    defaultFio: String,
    defaultPhone: String,
): SupportUiPhase = when (segment) {
    SupportSegment.None -> SupportUiPhase.Home
    SupportSegment.New -> SupportUiPhase.NewRequest(
        fio = defaultFio,
        phone = defaultPhone,
        model = "LiFePO4 АКБ",
    )
    SupportSegment.List -> SupportUiPhase.RequestList(requests = emptyList())
}

private fun statusRu(status: SupportRequestStatus): String = when (status) {
    SupportRequestStatus.Open -> "Открыто"
    SupportRequestStatus.Closed -> "Закрыто"
}

private fun statusColor(status: SupportRequestStatus): Color = when (status) {
    SupportRequestStatus.Open -> LiferychColors.Success
    SupportRequestStatus.Closed -> Color(0xFF787878)
}

@Composable
private fun SupportChrome(
    segment: SupportSegment,
    onSegment: (SupportSegment) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Parent Scaffold already reserves bottom-nav height via innerPadding.
    // imePadding keeps the form above the keyboard; navigationBarsPadding + section
    // spacing give the last controls (consent / ОТПРАВИТЬ) room to scroll fully clear.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = LiferychDimens.SectionSpacingLarge),
        ) {
            Column(Modifier.padding(horizontal = LiferychDimens.ScreenPadding)) {
                Text(
                    text = "Поддержка",
                    color = LiferychColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                SupportSegmentRow(selected = segment, onSelect = onSegment)
            }
            Column(
                modifier = Modifier.padding(
                    horizontal = LiferychDimens.ScreenPadding,
                    vertical = LiferychDimens.Space8,
                ),
                content = content,
            )
        }
    }
}

@Composable
private fun SupportSegmentRow(
    selected: SupportSegment,
    onSelect: (SupportSegment) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        SupportSegmentChip(
            title = "Новое обращение",
            selected = selected == SupportSegment.New,
            onClick = {
                onSelect(
                    if (selected == SupportSegment.New) SupportSegment.None else SupportSegment.New,
                )
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        SupportSegmentChip(
            title = "Ваши обращения",
            selected = selected == SupportSegment.List,
            onClick = {
                onSelect(
                    if (selected == SupportSegment.List) SupportSegment.None else SupportSegment.List,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SupportSegmentChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) LiferychColors.BrandYellow else LiferychColors.Surface
    val fg = if (selected) Color.White else LiferychColors.BrandYellowDark
    Text(
        text = title,
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, LiferychColors.BrandYellow, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    )
}

@Composable
private fun SupportHomeBody(
    segment: SupportSegment,
    onSegment: (SupportSegment) -> Unit,
    onDiagnostics: () -> Unit,
    onDial: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    SupportChrome(segment = segment, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Контакты техподдержки",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "ЛИФЕРЫЧ",
                color = LiferychColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "+7 (932) 078-10-11",
                color = LiferychColors.BrandYellowDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .clickable(onClick = onDial),
            )
            listOf(
                "Telegram" to "https://t.me/liferych",
                "MAX" to "https://max.ru/u/f9LHodD0cOIwPdSddb5TLuiLTMRCIkIpTzgUzr_f2iEj89DXpt_Mh2zXvcc",
                "ВКонтакте" to "https://vk.ru/liferych",
            ).forEach { (title, url) ->
                Text(
                    text = title,
                    color = Color(0xFF1976D2),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { onOpenUrl(url) },
                )
            }
            Text(
                text = "График: пн–пт 09:00–18:00",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        val diagShape = RoundedCornerShape(14.dp)
        Text(
            text = "ДИАГНОСТИКА",
            color = LiferychColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(diagShape)
                .background(LiferychColors.Surface)
                .border(1.dp, LiferychColors.BrandYellow, diagShape)
                .clickable(onClick = onDiagnostics)
                .padding(vertical = 12.dp, horizontal = 10.dp),
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SupportLoadingBody(onSegment: (SupportSegment) -> Unit) {
    SupportChrome(segment = SupportSegment.List, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Ваши обращения",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Загрузка обращений…",
                color = Color(0xFF646464),
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SupportEmptyBody(onSegment: (SupportSegment) -> Unit) {
    SupportChrome(segment = SupportSegment.List, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Ваши обращения",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Обращений пока нет. Выберите «Новое обращение», чтобы создать заявку.",
                color = Color(0xFF646464),
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SupportListBody(
    requests: List<SupportRequestUi>,
    total: Int = 0,
    page: Int = 1,
    totalPages: Int = 1,
    loading: Boolean = false,
    onSegment: (SupportSegment) -> Unit,
    onOpen: (SupportRequestUi) -> Unit,
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
) {
    SupportChrome(segment = SupportSegment.List, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Ваши обращения",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Всего обращений по этой BMS: ${maxOf(total, requests.size)}",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            requests.forEach { req ->
                SupportRequestCard(request = req, onOpen = { onOpen(req) })
                Spacer(Modifier.height(10.dp))
            }
            if (totalPages > 1 || total > 5) {
                Spacer(Modifier.height(4.dp))
                SupportPaginationRow(
                    page = page,
                    totalPages = totalPages,
                    enabled = !loading,
                    onPrevious = onPreviousPage,
                    onNext = onNextPage,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Legacy-style list pagination: ← Назад / N / M / Далее →.
 */
@Composable
private fun SupportPaginationRow(
    page: Int,
    totalPages: Int,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val safePages = maxOf(1, totalPages)
    val safePage = page.coerceIn(1, safePages)
    val prevEnabled = enabled && safePage > 1
    val nextEnabled = enabled && safePage < safePages
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SupportPaginationButton(
            label = "← Назад",
            enabled = prevEnabled,
            onClick = onPrevious,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$safePage / $safePages",
            color = Color(0xFF3C3C3C),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SupportPaginationButton(
            label = "Далее →",
            enabled = nextEnabled,
            onClick = onNext,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SupportPaginationButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val border = if (enabled) LiferychColors.BrandYellowDark else Color(0xFFDCDCDC)
    val textColor = if (enabled) LiferychColors.BrandYellowDark else Color(0xFFA0A0A0)
    Box(
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SupportRequestCard(
    request: SupportRequestUi,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val title = request.serverId?.takeIf { it.isNotBlank() }?.let { "№$it" } ?: "Локальный черновик"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFFF7F8FA))
            .border(1.dp, Color(0xFFE1E4EB), shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color(0xFF232323),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusRu(request.status),
                color = statusColor(request.status),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append("Дата: ${request.createdAt}\n")
                append("BMS: ${request.bmsUid}\n")
                append("Модель: ${request.model}\n")
                append("Проблема: ${request.problem.take(120)}")
                request.adminComment?.takeIf { it.isNotBlank() }?.let {
                    append("\nОтвет: ${it.take(160)}")
                }
            },
            color = Color(0xFF505050),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        val btnShape = RoundedCornerShape(12.dp)
        Text(
            text = "Открыть / редактировать",
            color = LiferychColors.BrandYellowDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(btnShape)
                .background(LiferychColors.Surface)
                .border(1.dp, LiferychColors.BrandYellow, btnShape)
                .clickable(onClick = onOpen)
                .padding(vertical = 10.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun SupportNewBody(
    form: SupportUiPhase.NewRequest,
    submitting: Boolean,
    attachmentRows: List<SupportAttachmentUi>,
    onSegment: (SupportSegment) -> Unit,
    onFormChange: (SupportUiPhase.NewRequest) -> Unit,
    onSubmit: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
) {
    val canSubmit = isSupportFormReady(
        fio = form.fio,
        phone = form.phone,
        model = form.model,
        problem = form.problem,
        consent = form.consent,
    ) && !submitting
    SupportChrome(segment = SupportSegment.New, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Новое гарантийное обращение",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "При отправке приложение приложит текущие логи, ошибки и параметры АКБ, если BMS подключена.",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = form.fio,
                hint = "ФИО клиента — Иванов Иван",
                onValueChange = { onFormChange(form.copy(fio = it, statusMessage = "")) },
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = form.phone,
                hint = "Телефон — +7 (___) ___-__-__",
                onValueChange = { onFormChange(form.copy(phone = it, statusMessage = "")) },
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = form.model,
                hint = "Модель АКБ",
                onValueChange = { onFormChange(form.copy(model = it, statusMessage = "")) },
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = form.problem,
                hint = "Описание проблемы — Что произошло, когда проявилась проблема, как заряжалась/эксплуатировалась АКБ",
                onValueChange = { onFormChange(form.copy(problem = it, statusMessage = "")) },
                minHeight = 120.dp,
                singleLine = false,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            AttachmentButtons(
                onPhoto = onTakePhoto,
                onGallery = onPickGallery,
                enabled = !submitting,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "Прикреплённые файлы",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space4))
            AttachmentList(
                items = attachmentRows,
                onRemove = onRemoveAttachment,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            ConsentRow(
                checked = form.consent,
                onToggle = { onFormChange(form.copy(consent = !form.consent, statusMessage = "")) },
            )
            Spacer(Modifier.height(LiferychDimens.Space12))
            PrimaryYellowButton(
                text = "ОТПРАВИТЬ",
                enabled = canSubmit,
                onClick = onSubmit,
            )
            if (form.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(LiferychDimens.Space8))
                Text(
                    text = form.statusMessage,
                    color = Color(0xFF5A5A5A),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(LiferychDimens.Space16))
    }
}

@Composable
private fun SupportDetailsBody(
    phase: SupportUiPhase.RequestDetails,
    submitting: Boolean,
    attachmentRows: List<SupportAttachmentUi>,
    onSegment: (SupportSegment) -> Unit,
    onFormChange: (SupportUiPhase.RequestDetails) -> Unit,
    onSubmit: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
) {
    val titleId = phase.request.serverId?.takeIf { it.isNotBlank() } ?: "черновик"
    val canSubmit = isSupportFormReady(
        fio = phase.fio,
        phone = phase.phone,
        model = phase.model,
        problem = phase.problem,
        consent = phase.consent,
    ) && !submitting
    SupportChrome(segment = SupportSegment.List, onSegment = onSegment) {
        SupportBorderedCard {
            Text(
                text = "Обращение №$titleId",
                color = Color(0xFF323232),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space4))
            Text(
                text = statusRu(phase.request.status),
                color = statusColor(phase.request.status),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "При отправке приложение приложит текущие логи, ошибки и параметры АКБ, если BMS подключена.",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = phase.fio,
                hint = "ФИО клиента",
                onValueChange = { onFormChange(phase.copy(fio = it)) },
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = phase.phone,
                hint = "Телефон",
                onValueChange = { onFormChange(phase.copy(phone = it)) },
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = phase.model,
                hint = "Модель АКБ",
                onValueChange = { onFormChange(phase.copy(model = it)) },
                minHeight = 52.dp,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            SupportField(
                value = phase.problem,
                hint = "Описание проблемы",
                onValueChange = { onFormChange(phase.copy(problem = it)) },
                minHeight = 120.dp,
                singleLine = false,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            AttachmentButtons(
                onPhoto = onTakePhoto,
                onGallery = onPickGallery,
                enabled = !submitting,
            )
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "Прикреплённые файлы",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space4))
            AttachmentList(items = attachmentRows, onRemove = onRemoveAttachment)
            Spacer(Modifier.height(LiferychDimens.Space8))
            ConsentRow(
                checked = phase.consent,
                onToggle = { onFormChange(phase.copy(consent = !phase.consent)) },
            )
            Spacer(Modifier.height(LiferychDimens.Space12))
            PrimaryYellowButton(
                text = "ОТПРАВИТЬ",
                enabled = canSubmit,
                onClick = onSubmit,
            )
            if (phase.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(LiferychDimens.Space8))
                Text(
                    text = phase.statusMessage,
                    color = Color(0xFF5A5A5A),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(LiferychDimens.Space16))
    }
}

@Composable
private fun SupportErrorBody(
    message: String,
    onBackHome: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LiferychDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_liferych_warning),
            contentDescription = null,
            tint = LiferychColors.Warning,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = LiferychColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Назад к поддержке",
            color = LiferychColors.BrandYellowDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onBackHome),
        )
    }
}

@Composable
private fun SupportBorderedCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(12.dp),
        content = content,
    )
}

@Composable
private fun SupportField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    minHeight: Dp = 48.dp,
    singleLine: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(Color(0xFFF7F8FA))
            .border(1.dp, Color(0xFFE1E4EB), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) {
            Text(text = hint, color = Color(0xFF8C8C8C), fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = Color(0xFF232323),
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(LiferychColors.BrandYellowDark),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AttachmentButtons(
    onPhoto: () -> Unit,
    onGallery: () -> Unit,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlineActionButton(
            text = "Сделать фото",
            onClick = onPhoto,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlineActionButton(
            text = "Из галереи",
            onClick = onGallery,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AttachmentList(
    items: List<SupportAttachmentUi>,
    onRemove: (Int) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = "Файлы не выбраны",
            color = LiferychColors.TextSecondary,
            fontSize = 13.sp,
        )
        return
    }
    items.forEachIndexed { index, item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LiferychDimens.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📎 ${item.displayName}",
                color = LiferychColors.TextPrimary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "✕",
                color = Color(0xFF8C8C8C),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onRemove(index) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text,
        color = if (enabled) LiferychColors.TextPrimary else Color(0xFF9A9A9A),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, Color(0xFFCAD3DC), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle, role = Role.Checkbox)
            .padding(vertical = LiferychDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = if (checked) {
                "Согласие отмечено"
            } else {
                "Согласие не отмечено"
            },
            tint = LiferychColors.BrandYellowDark,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(LiferychDimens.Space12))
        Text(
            text = "Согласен на обработку персональных данных",
            color = LiferychColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * UI-only readiness for the submit button (no API / validation rewrite).
 */
private fun isSupportFormReady(
    fio: String,
    phone: String,
    model: String,
    problem: String,
    consent: Boolean,
): Boolean {
    return fio.isNotBlank() &&
        phone.isNotBlank() &&
        model.isNotBlank() &&
        problem.isNotBlank() &&
        consent
}

@Composable
private fun PrimaryYellowButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (enabled) LiferychColors.BrandYellow else Color(0xFFE8E8E8)
    val foreground = if (enabled) LiferychColors.TextPrimary else Color(0xFF9A9A9A)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Demo request for details / list previews — UI only. */
fun demoSupportRequests(): List<SupportRequestUi> = listOf(
    SupportRequestUi(
        localId = "local-1",
        serverId = "1042",
        status = SupportRequestStatus.Open,
        createdAt = "2026-09-15 14:20",
        bmsUid = "DALY-BMS-16S",
        model = "LiFePO4 АКБ",
        problem = "Сильный разбег ячеек после зарядки",
        fio = "Демо пользователь",
        phone = "+7 (900) 000-00-00",
        adminComment = null,
        attachmentNames = listOf("photo_1.jpg"),
    ),
    SupportRequestUi(
        localId = "local-2",
        serverId = "998",
        status = SupportRequestStatus.Closed,
        createdAt = "2026-08-02 11:05",
        bmsUid = "DALY-BMS-16S",
        model = "LiFePO4 АКБ",
        problem = "Вопрос по гарантии",
        fio = "Демо пользователь",
        phone = "+7 (900) 000-00-00",
        adminComment = "Обращение закрыто",
    ),
)

@Preview(showBackground = true, name = "SupportHomePreview", heightDp = 860)
@Composable
private fun SupportHomePreview() {
    LiferychTheme { SupportScreen(onBack = {}) }
}

@Preview(showBackground = true, name = "SupportNewRequestPreview", heightDp = 1000)
@Composable
private fun SupportNewRequestPreview() {
    LiferychTheme {
        SupportScreen(
            onBack = {},
            initialPhase = SupportUiPhase.NewRequest(
                fio = "Демо пользователь",
                phone = "+7 (900) 000-00-00",
            ),
        )
    }
}

@Preview(showBackground = true, name = "SupportEmptyPreview", heightDp = 800)
@Composable
private fun SupportEmptyPreview() {
    LiferychTheme {
        SupportScreen(
            onBack = {},
            initialPhase = SupportUiPhase.RequestList(emptyList()),
        )
    }
}

@Preview(showBackground = true, name = "SupportRequestDetailsPreview", heightDp = 1000)
@Composable
private fun SupportRequestDetailsPreview() {
    val req = demoSupportRequests().first()
    LiferychTheme {
        SupportScreen(
            onBack = {},
            initialPhase = SupportUiPhase.RequestDetails(
                request = req,
                fio = req.fio,
                phone = req.phone,
                model = req.model,
                problem = req.problem,
                attachments = req.attachmentNames,
                statusMessage = "Текущий статус: Открыто",
            ),
        )
    }
}

@Preview(showBackground = true, name = "SupportErrorPreview", heightDp = 700)
@Composable
private fun SupportErrorPreview() {
    LiferychTheme {
        SupportScreen(
            onBack = {},
            initialPhase = SupportUiPhase.Error("Не удалось обновить статусы обращений"),
        )
    }
}
