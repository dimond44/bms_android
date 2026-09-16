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
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme

/** Client-facing warranty status labels (legacy: Открыто / Закрыто). */
enum class SupportRequestStatus {
    Open,
    Closed,
}

/**
 * Fake UI model for a warranty support request card / details.
 * Not persisted / not sent to API in this visual stage.
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
 * CLIENT Support screen — visual port of legacy [showSupportScreen]
 * (contacts home, warranty form, request list). No network / API in this stage.
 *
 * @param onBack leave support tab
 * @param initialPhase phase for screenshots / previews
 * @param defaultFio prefill for new form (profile)
 * @param defaultPhone prefill for new form (profile)
 * @param modifier layout modifier
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPhase: SupportUiPhase = SupportUiPhase.Home,
    defaultFio: String = "Демо пользователь",
    defaultPhone: String = "+7 (900) 000-00-00",
) {
    var phase by remember { mutableStateOf(initialPhase) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Поддержка", onBack = onBack)

        when (val current = phase) {
            SupportUiPhase.Home -> SupportHomeBody(
                segment = SupportSegment.None,
                onSegment = { phase = segmentToPhase(it, defaultFio, defaultPhone) },
                onDiagnostics = {
                    phase = SupportUiPhase.Error(
                        "Диагностика BMS будет перенесена на следующем этапе",
                    )
                },
            )

            is SupportUiPhase.NewRequest -> SupportNewBody(
                form = current,
                onSegment = { phase = segmentToPhase(it, defaultFio, defaultPhone) },
                onFormChange = { phase = it },
                onSubmit = {
                    if (!current.consent) {
                        phase = current.copy(statusMessage = "Подтвердите согласие на обработку данных")
                    } else if (current.problem.isBlank()) {
                        phase = current.copy(statusMessage = "Опишите проблему")
                    } else {
                        phase = current.copy(
                            statusMessage = "Отправка недоступна в UI-preview (API не подключён)",
                        )
                    }
                },
            )

            is SupportUiPhase.RequestList -> {
                if (current.requests.isEmpty()) {
                    SupportEmptyBody(
                        onSegment = { phase = segmentToPhase(it, defaultFio, defaultPhone) },
                    )
                } else {
                    SupportListBody(
                        requests = current.requests,
                        onSegment = { phase = segmentToPhase(it, defaultFio, defaultPhone) },
                        onOpen = { req ->
                            phase = SupportUiPhase.RequestDetails(
                                request = req,
                                fio = req.fio,
                                phone = req.phone,
                                model = req.model,
                                problem = req.problem,
                                attachments = req.attachmentNames,
                                statusMessage = "Текущий статус: ${statusRu(req.status)}",
                            )
                        },
                    )
                }
            }

            is SupportUiPhase.RequestDetails -> SupportDetailsBody(
                phase = current,
                onSegment = { phase = segmentToPhase(it, defaultFio, defaultPhone) },
                onFormChange = { phase = it },
                onSubmit = {
                    phase = current.copy(
                        statusMessage = "Отправка недоступна в UI-preview (API не подключён)",
                    )
                },
            )

            is SupportUiPhase.Error -> SupportErrorBody(
                message = current.message,
                onBackHome = { phase = SupportUiPhase.Home },
            )
        }
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
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            listOf("Telegram", "MAX", "ВКонтакте").forEach { link ->
                Text(
                    text = link,
                    color = Color(0xFF1976D2),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
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
    onSegment: (SupportSegment) -> Unit,
    onOpen: (SupportRequestUi) -> Unit,
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
            requests.forEach { req ->
                SupportRequestCard(request = req, onOpen = { onOpen(req) })
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
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
    onSegment: (SupportSegment) -> Unit,
    onFormChange: (SupportUiPhase.NewRequest) -> Unit,
    onSubmit: () -> Unit,
) {
    val canSubmit = isSupportFormReady(
        fio = form.fio,
        phone = form.phone,
        model = form.model,
        problem = form.problem,
        consent = form.consent,
    )
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
            // File pickers stay visual-only for now (no gallery/camera backend).
            AttachmentButtons(onPhoto = {}, onGallery = {})
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "Прикреплённые файлы",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space4))
            if (form.attachments.isEmpty()) {
                Text(
                    text = "Файлы не выбраны",
                    color = LiferychColors.TextSecondary,
                    fontSize = 13.sp,
                )
            } else {
                form.attachments.forEach { name ->
                    Text(
                        text = name,
                        color = LiferychColors.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = LiferychDimens.Space4),
                    )
                }
            }
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
    onSegment: (SupportSegment) -> Unit,
    onFormChange: (SupportUiPhase.RequestDetails) -> Unit,
    onSubmit: () -> Unit,
) {
    val titleId = phase.request.serverId?.takeIf { it.isNotBlank() } ?: "черновик"
    val canSubmit = isSupportFormReady(
        fio = phase.fio,
        phone = phase.phone,
        model = phase.model,
        problem = phase.problem,
        consent = phase.consent,
    )
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
            AttachmentButtons(onPhoto = {}, onGallery = {})
            Spacer(Modifier.height(LiferychDimens.Space8))
            Text(
                text = "Прикреплённые файлы",
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(LiferychDimens.Space4))
            if (phase.attachments.isEmpty()) {
                Text(
                    text = "Файлы не выбраны",
                    color = LiferychColors.TextSecondary,
                    fontSize = 13.sp,
                )
            } else {
                phase.attachments.forEach { name ->
                    Text(text = name, color = LiferychColors.TextPrimary, fontSize = 13.sp)
                }
            }
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
            imageVector = Icons.Outlined.WarningAmber,
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
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlineActionButton(
            text = "Сделать фото",
            onClick = onPhoto,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlineActionButton(
            text = "Из галереи",
            onClick = onGallery,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text,
        color = LiferychColors.TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, Color(0xFFCAD3DC), shape)
            .clickable(onClick = onClick)
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
