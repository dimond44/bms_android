package ru.liferych.bms.ui.screens.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.data.bms.DalyProtocol
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.model.ActiveBmsErrorUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme

/**
 * CLIENT Journal — visual/semantic port of legacy
 * [ru.liferych.bms.MainActivity.showJournalScreen]: текущие активные ошибки BMS (0x98),
 * не история событий подключения/зарядки/балансировки.
 *
 * @param activeErrors titles from [ru.liferych.bms.domain.model.BatteryState.errors]
 * @param onBack navigate back
 * @param modifier layout modifier
 */
@Composable
fun JournalScreen(
    activeErrors: List<ActiveBmsErrorUi>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background),
    ) {
        // Legacy journal header: «← Журнал» (no brand logo)
        LiferychTopBar(title = "Журнал", onBack = onBack)

        if (activeErrors.isEmpty()) {
            JournalEmptyContent(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(
                    horizontal = LiferychDimens.ScreenPadding,
                    vertical = 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    JournalPageTitle()
                }
                itemsIndexed(activeErrors, key = { index, error -> "${index}_${error.title}" }) { _, error ->
                    ActiveBmsErrorRow(error = error)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/**
 * Page title matching legacy «Журнал BMS» (22sp / weight 750).
 */
@Composable
private fun JournalPageTitle(modifier: Modifier = Modifier) {
    Text(
        text = "Журнал BMS",
        color = Color(0xFF101114),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 4.dp, bottom = 14.dp),
    )
}

/**
 * Legacy empty state: title + centered «Активных ошибок нет», lots of whitespace.
 * No Material card / icon placeholder.
 *
 * @param modifier layout modifier from parent Column (expects weight)
 */
@Composable
private fun JournalEmptyContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = LiferychDimens.ScreenPadding),
    ) {
        JournalPageTitle()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "Активных ошибок нет",
                color = Color(0xFF646464),
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

/**
 * Port of legacy [journalActiveErrorRow]: bordered white row, warning icon + title only.
 *
 * @param error active fault title from Daly 0x98
 * @param modifier layout modifier
 */
@Composable
private fun ActiveBmsErrorRow(
    error: ActiveBmsErrorUi,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Color(0xFFC83C3C),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = error.title,
            color = Color(0xFF282828),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Maps [BatteryState.errors] string titles to journal UI rows.
 *
 * @param errors active Daly 0x98 descriptions
 * @return UI models (title only; no invented timestamps)
 */
fun activeErrorsFromBattery(errors: List<String>): List<ActiveBmsErrorUi> {
    return errors
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { ActiveBmsErrorUi(title = it) }
}

@Preview(showBackground = true, name = "JournalNoErrorsPreview", heightDp = 800)
@Composable
private fun JournalNoErrorsPreview() {
    LiferychTheme {
        JournalScreen(
            activeErrors = emptyList(),
            onBack = {},
        )
    }
}

/**
 * Preview with real Daly 0x98 bit descriptions from [DalyProtocol.errorDescription].
 * For UI layout only — not a historical event log.
 */
@Preview(showBackground = true, name = "JournalErrorsPreview", heightDp = 800)
@Composable
private fun JournalErrorsPreview() {
    LiferychTheme {
        JournalScreen(
            activeErrors = listOf(
                ActiveBmsErrorUi(DalyProtocol.errorDescription(0)),
                ActiveBmsErrorUi(DalyProtocol.errorDescription(24)),
                ActiveBmsErrorUi(DalyProtocol.errorDescription(50)),
            ),
            onBack = {},
        )
    }
}
