package ru.liferych.bms.ui.screens.mybatteries

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.data.local.SavedBattery
import ru.liferych.bms.ui.components.LiferychBrandHeader
import ru.liferych.bms.ui.devicesearch.sanitizeBleDisplayName
import ru.liferych.bms.ui.devicesearch.savedBatteryUiTitle
import ru.liferych.bms.ui.model.SavedBatteryCardStatus
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * CLIENT home tab: saved batteries (legacy «Мои батареи» structure).
 *
 * Visual tokens match the approved Compose design system
 * (Dashboard / Journal / Profile). Does not list BLE scan results.
 *
 * @param batteries persisted [SavedBattery] list
 * @param cardStatus presence map keyed by uppercase MAC
 * @param onAddBattery open DeviceSearch (only when [canAddBattery])
 * @param onBatteryClick connect / open Dashboard
 * @param onRename long-press rename
 * @param onDelete remove from store
 * @param onAppear soft presence scan hook
 * @param canAddBattery show «ДОБАВИТЬ БАТАРЕЮ» (Authorized only; Guest hides it)
 * @param modifier layout modifier
 */
@Composable
fun MyBatteriesScreen(
    batteries: List<SavedBattery>,
    cardStatus: Map<String, SavedBatteryCardStatus>,
    onAddBattery: () -> Unit,
    onBatteryClick: (SavedBattery) -> Unit,
    onRename: (SavedBattery, String) -> Unit,
    onDelete: (SavedBattery) -> Unit,
    onAppear: () -> Unit = {},
    canAddBattery: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var renameTarget by remember { mutableStateOf<SavedBattery?>(null) }

    LaunchedEffect(Unit) {
        onAppear()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychBrandHeader()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LiferychDimens.ScreenPadding,
                end = LiferychDimens.ScreenPadding,
                top = 0.dp,
                bottom = 14.dp,
            ),
        ) {
            item {
                Text(
                    text = "Мои батареи",
                    style = LiferychTypography.headlineLarge,
                    color = LiferychColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            if (canAddBattery) {
                item {
                    AddBatteryButton(
                        onClick = onAddBattery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                    )
                }
            }
            item {
                Text(
                    text = "Добавленные устройства",
                    style = LiferychTypography.bodyLarge,
                    color = LiferychColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (batteries.isEmpty()) {
                item {
                    EmptySavedBatteriesHint(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                    )
                }
            } else {
                items(batteries, key = { it.address.uppercase() }) { battery ->
                    val key = battery.address.uppercase()
                    SavedBatteryCard(
                        battery = battery,
                        status = cardStatus[key] ?: SavedBatteryCardStatus.Checking,
                        onClick = { onBatteryClick(battery) },
                        onLongClick = { renameTarget = battery },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                    )
                }
            }
            item {
                HelpHintBlock(
                    canAddBattery = canAddBattery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameBatteryDialog(
            battery = target,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                onRename(target, name)
                renameTarget = null
            },
            onDelete = {
                onDelete(target)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun AddBatteryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(56.dp)
            .shadow(LiferychDimens.CardElevation, shape)
            .clip(shape)
            .background(LiferychColors.BrandYellow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ДОБАВИТЬ БАТАРЕЮ",
            color = LiferychColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedBatteryCard(
    battery: SavedBattery,
    status: SavedBatteryCardStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    val capacityText = battery.capacityAh
        ?.takeIf { it > 0.0 }
        ?.let { "%.0f А·ч".format(it) }
        ?: "— А·ч"

    Column(
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface, shape)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = savedBatteryUiTitle(
                    customName = battery.customName,
                    bluetoothName = battery.bluetoothName,
                    address = battery.address,
                ),
                color = LiferychColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = capacityText,
                color = LiferychColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        PresenceStatusPill(status = status)
        if (status == SavedBatteryCardStatus.Disconnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "BMS находится в спящем режиме.\n" +
                    "Чтобы вывести её из спящего режима, подключите зарядное устройство " +
                    "или любой потребитель к клеммам аккумулятора.",
                color = LiferychColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun PresenceStatusPill(
    status: SavedBatteryCardStatus,
    modifier: Modifier = Modifier,
) {
    val (bg, text, color) = when (status) {
        SavedBatteryCardStatus.Connected -> Triple(
            LiferychColors.SuccessSoft,
            "Батарея в сети",
            LiferychColors.SuccessAlt,
        )
        SavedBatteryCardStatus.Checking -> Triple(
            LiferychColors.SurfaceMuted,
            "Проверяем состояние батареи…",
            LiferychColors.TextSecondary,
        )
        SavedBatteryCardStatus.Disconnected -> Triple(
            LiferychColors.SurfaceMuted,
            "Не в сети",
            LiferychColors.TextSecondary,
        )
        SavedBatteryCardStatus.ConnectionError -> Triple(
            LiferychColors.ErrorSoft,
            "Ошибка подключения",
            LiferychColors.Error,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == SavedBatteryCardStatus.Checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = LiferychColors.BrandYellow,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptySavedBatteriesHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LiferychDimens.CardRadius))
            .background(LiferychColors.SurfaceMuted)
            .border(1.dp, LiferychColors.Border, RoundedCornerShape(LiferychDimens.CardRadius))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Сохранённых батарей пока нет",
            style = LiferychTypography.bodyMedium,
            color = LiferychColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HelpHintBlock(
    canAddBattery: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = if (canAddBattery) {
        "Нажмите на батарею, чтобы открыть её параметры. " +
            "Удерживайте карточку, чтобы задать имя. " +
            "Если батарей ещё нет, нажмите кнопку выше."
    } else {
        "Нажмите на батарею, чтобы открыть её параметры. " +
            "Удерживайте карточку, чтобы задать имя."
    }
    Text(
        text = text,
        style = LiferychTypography.bodySmall,
        color = LiferychColors.TextSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LiferychColors.SurfaceMuted)
            .border(1.dp, Color(0xFFCAD3DC), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun RenameBatteryDialog(
    battery: SavedBattery,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            battery.customName.ifBlank {
                sanitizeBleDisplayName(battery.bluetoothName)
                    .ifBlank { battery.bluetoothName }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название батареи") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Название батареи") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Удалить")
                }
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        },
    )
}

@Preview(showBackground = true, name = "MyBatteriesScreenPreview")
@Composable
private fun MyBatteriesScreenPreview() {
    LiferychTheme {
        MyBatteriesScreen(
            batteries = listOf(
                SavedBattery(
                    address = "D2:1A:07:12:2C:C4",
                    bluetoothName = "DL-D21A07122CC4",
                    customName = "",
                    soc = 99.0,
                    capacityAh = 105.0,
                    lastSeenAt = System.currentTimeMillis(),
                ),
            ),
            cardStatus = mapOf(
                "D2:1A:07:12:2C:C4" to SavedBatteryCardStatus.Connected,
            ),
            onAddBattery = {},
            onBatteryClick = {},
            onRename = { _, _ -> },
            onDelete = {},
        )
    }
}
