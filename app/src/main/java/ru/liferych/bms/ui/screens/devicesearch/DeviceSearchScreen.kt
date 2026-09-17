package ru.liferych.bms.ui.screens.devicesearch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.devicesearch.sanitizeBleDisplayName
import ru.liferych.bms.ui.model.BatterySummaryUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

/**
 * BLE device search — legacy structure, Compose visual system.
 *
 * Structure from [ru.liferych.bms.MainActivity.showSearchScreen]:
 * back title → «Батарея» → available list / refresh → ID filter → device cards.
 * Does not change scan/connect backend — only presentation + local ID filter.
 *
 * @param devices discovered BLE devices (already soft-sorted by repository)
 * @param connectionState repository connection/scan state
 * @param connectingAddress MAC currently connecting, if any
 * @param onRefreshScan start/restart BLE scan
 * @param onConnect tap «Подключить» on a device card
 * @param onBack return to «Мои батареи»
 * @param modifier layout modifier
 */
@Composable
fun DeviceSearchScreen(
    devices: List<BatterySummaryUi>,
    connectionState: BmsConnectionState,
    onRefreshScan: () -> Unit,
    onConnect: (BatterySummaryUi) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    connectingAddress: String? = null,
) {
    var idQuery by remember { mutableStateOf("") }
    val scanning = connectionState is BmsConnectionState.Scanning
    val filtered = remember(devices, idQuery) {
        devices.filter { deviceMatchesIdQuery(it, idQuery) }
    }
    val connecting = connectingAddress != null &&
        connectionState is BmsConnectionState.Connecting

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        LiferychTopBar(title = "Поиск устройств", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LiferychDimens.ScreenPadding,
                end = LiferychDimens.ScreenPadding,
                top = 0.dp,
                bottom = 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Text(
                    text = "Батарея",
                    style = LiferychTypography.headlineLarge,
                    color = LiferychColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            item {
                Text(
                    text = "Доступные устройства",
                    style = LiferychTypography.bodyLarge,
                    color = LiferychColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            item {
                Text(
                    text = "Обновить список",
                    color = LiferychColors.BrandYellowDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onRefreshScan)
                        .padding(bottom = 8.dp, top = 2.dp),
                )
            }
            item {
                IdSearchField(
                    value = idQuery,
                    onValueChange = { idQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
            }
            item {
                Text(
                    text = "Можно ввести последние цифры Bluetooth ID",
                    style = LiferychTypography.bodySmall,
                    color = LiferychColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            when {
                scanning && filtered.isEmpty() -> {
                    item {
                        ScanningState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                        )
                    }
                }
                !scanning && filtered.isEmpty() -> {
                    item {
                        EmptySearchState(
                            onRetry = onRefreshScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                        )
                    }
                }
                else -> {
                    items(filtered, key = { it.id }) { device ->
                        val isConnecting = connectingAddress.equals(device.address, true)
                        DeviceSearchCard(
                            device = device,
                            connecting = isConnecting,
                            enabled = !connecting || isConnecting,
                            onConnect = { onConnect(device) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                        )
                    }
                }
            }

            item {
                HelpHint(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun IdSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.InnerCardRadius)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(LiferychColors.SurfaceMuted)
            .border(1.dp, LiferychColors.Border, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = "Поиск по ID, например 3A2F",
                style = LiferychTypography.bodyMedium,
                color = LiferychColors.TextTertiary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = LiferychColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = SolidColor(LiferychColors.BrandYellowDark),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScanningState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = LiferychColors.BrandYellow,
            trackColor = LiferychColors.BrandYellowSoft,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Поиск устройств…",
            style = LiferychTypography.bodyMedium,
            color = LiferychColors.TextSecondary,
        )
    }
}

@Composable
private fun EmptySearchState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Устройства не найдены",
            style = LiferychTypography.titleMedium,
            color = LiferychColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LiferychColors.BrandYellow)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Повторить поиск",
                color = LiferychColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun DeviceSearchCard(
    device: BatterySummaryUi,
    connecting: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    val selectedBorder = if (connecting) {
        LiferychColors.BrandYellowDark
    } else {
        LiferychColors.Border
    }
    // Compact card: full device ID + signal bars + connect — no MAC / RSSI text / BT icon.
    Row(
        modifier = modifier
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, selectedBorder, shape)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Display-only: strip NUL/garbage from AD name; raw [device.name] stays for connect.
            val displayId = sanitizeBleDisplayName(device.name)
                .ifBlank { device.address ?: device.id }
            Text(
                text = displayId,
                color = LiferychColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (connecting) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Подключение к BMS…",
                    color = LiferychColors.BrandYellowDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        SignalBars(rssi = device.rssi)
        Spacer(Modifier.width(6.dp))
        ConnectActionButton(
            connecting = connecting,
            enabled = enabled && !connecting,
            onClick = onConnect,
        )
    }
}

/**
 * Maps BLE RSSI (dBm) to a 1…5 UI bar level for the signal indicator.
 *
 * @param rssi measured RSSI from scan (still kept on the model; not shown as text)
 * @return filled bar count in range 1..5
 */
fun rssiToSignalBars(rssi: Int): Int {
    return when {
        rssi >= -60 -> 5
        rssi >= -67 -> 4
        rssi >= -75 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
}

/**
 * Active bar tint by signal quality (no red for weak RSSI).
 *
 * @param bars filled bar count from [rssiToSignalBars]
 * @return accent / neutral / muted color for filled bars
 */
fun signalBarsActiveColor(bars: Int): Color {
    return when {
        bars >= 4 -> LiferychColors.BrandYellowDark
        bars == 3 -> LiferychColors.IconDefault
        else -> LiferychColors.TextTertiary
    }
}

/**
 * Compact 5-bar Bluetooth signal indicator (Liferych palette).
 *
 * @param rssi measured RSSI; stronger signal fills more bars
 * @param modifier layout modifier
 */
@Composable
fun SignalBars(
    rssi: Int,
    modifier: Modifier = Modifier,
) {
    val levels = rssiToSignalBars(rssi)
    val active = signalBarsActiveColor(levels)
    val muted = LiferychColors.Divider
    Row(
        modifier = modifier.height(22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val heights = listOf(6.dp, 10.dp, 14.dp, 18.dp, 22.dp)
        heights.forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < levels) active else muted),
            )
        }
    }
}

@Composable
private fun ConnectActionButton(
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(LiferychDimens.InnerCardRadius)
    val bg = if (connecting) {
        LiferychColors.BrandYellowSoft
    } else {
        Color(0xFFFFFAE8)
    }
    val border = LiferychColors.BrandYellowDark
    val label = if (connecting) "Подключение…" else "Подключить"
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(36.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled || connecting) {
                LiferychColors.BrandYellowDark
            } else {
                LiferychColors.TextTertiary
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HelpHint(modifier: Modifier = Modifier) {
    Text(
        text = "Нажмите «Подключить» напротив нужной батареи. " +
            "После успешного подключения вы вернётесь на экран «Мои батареи», " +
            "где сможете открыть её параметры.",
        style = LiferychTypography.bodySmall,
        color = LiferychColors.TextSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LiferychColors.SurfaceMuted)
            .border(1.dp, Color(0xFFCAD3DC), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * Local ID / name filter matching legacy [MainActivity.deviceMatchesSearch].
 *
 * @param device list item
 * @param query raw user input from the search field
 * @return true when the device should stay visible
 */
fun deviceMatchesIdQuery(device: BatterySummaryUi, query: String): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    val compactAddress = (device.address ?: device.id)
        .replace(":", "")
        .replace("-", "")
    val compactQuery = q.replace(":", "").replace("-", "").replace(" ", "")
    if (compactQuery.isBlank()) return true
    val display = sanitizeBleDisplayName(device.name)
    return compactAddress.contains(compactQuery, ignoreCase = true) ||
        display.contains(q, ignoreCase = true) ||
        device.name.contains(q, ignoreCase = true)
}

@Preview(showBackground = true, name = "DeviceSearchScreenPreview", heightDp = 820)
@Composable
private fun DeviceSearchScreenPreview() {
    LiferychTheme {
        DeviceSearchScreen(
            devices = FrontendViewModel.demoBatteries().map {
                it.copy(rssi = -58, address = it.address ?: "AA:BB:CC:DD:EE:01")
            },
            connectionState = BmsConnectionState.Disconnected,
            onRefreshScan = {},
            onConnect = {},
            onBack = {},
        )
    }
}
