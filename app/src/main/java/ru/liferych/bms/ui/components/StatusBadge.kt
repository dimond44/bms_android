package ru.liferych.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

enum class StatusTone {
    Neutral,
    Success,
    Warning,
    Error,
}

@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        StatusTone.Neutral -> LiferychColors.SurfaceElevated to LiferychColors.TextSecondary
        StatusTone.Success -> LiferychColors.SuccessSoft to LiferychColors.Success
        StatusTone.Warning -> LiferychColors.WarningSoft to LiferychColors.Warning
        StatusTone.Error -> LiferychColors.ErrorSoft to LiferychColors.Error
    }
    // Soft tonal chip; for Neutral on Surface cards use a slightly stronger fill.
    val background = if (tone == StatusTone.Neutral) LiferychColors.Divider.copy(alpha = 0.55f) else bg
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LiferychDimens.ChipRadius))
            .background(background)
            .padding(
                horizontal = LiferychDimens.Space8,
                vertical = LiferychDimens.Space4,
            ),
    ) {
        Text(text = text, style = LiferychTypography.labelMedium.copy(color = fg))
    }
}

fun BmsConnectionState.toStatusLabel(): Pair<String, StatusTone> {
    return when (this) {
        is BmsConnectionState.Connected -> "Подключено" to StatusTone.Success
        is BmsConnectionState.Connecting -> "Подключение…" to StatusTone.Warning
        is BmsConnectionState.Scanning -> "Поиск…" to StatusTone.Warning
        is BmsConnectionState.Disconnected -> "Отключено" to StatusTone.Neutral
        is BmsConnectionState.Error -> "Ошибка" to StatusTone.Error
    }
}
