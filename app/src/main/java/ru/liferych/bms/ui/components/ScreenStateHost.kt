package ru.liferych.bms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.liferych.bms.R
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Shared empty / loading / error / disconnected chrome for screens.
 */
@Composable
fun ScreenStateHost(
    status: ScreenUiStatus,
    connectedContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    loadingMessage: String = "Подключение…",
    disconnectedMessage: String = "Нет связи с BMS",
    disconnectedSubtitle: String = "Проверьте Bluetooth и питание BMS",
    emptyMessage: String = "Нет данных",
    errorMessage: String = "Ошибка подключения",
    errorSubtitle: String = "Попробуйте подключиться снова",
    emptyContent: (@Composable () -> Unit)? = null,
) {
    when (status) {
        ScreenUiStatus.Loading -> {
            StatePlaceholder(
                icon = null,
                title = loadingMessage,
                subtitle = null,
                loading = true,
                modifier = modifier,
            )
        }

        ScreenUiStatus.Disconnected -> {
            StatePlaceholder(
                iconRes = R.drawable.ic_liferych_bluetooth,
                title = disconnectedMessage,
                subtitle = disconnectedSubtitle,
                iconTint = LiferychColors.IconMuted,
                modifier = modifier,
            )
        }

        ScreenUiStatus.Empty -> {
            if (emptyContent != null) {
                Box(modifier = modifier.fillMaxSize()) { emptyContent() }
            } else {
                StatePlaceholder(
                    iconRes = R.drawable.ic_liferych_journal,
                    title = emptyMessage,
                    subtitle = null,
                    iconTint = LiferychColors.IconMuted,
                    modifier = modifier,
                )
            }
        }

        ScreenUiStatus.Error -> {
            StatePlaceholder(
                iconRes = R.drawable.ic_liferych_error,
                title = errorMessage,
                subtitle = errorSubtitle,
                iconTint = LiferychColors.Error,
                modifier = modifier,
            )
        }

        ScreenUiStatus.Connected -> connectedContent()
    }
}

@Composable
fun StatePlaceholder(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    iconTint: androidx.compose.ui.graphics.Color = LiferychColors.IconMuted,
) {
    StatePlaceholderContent(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        loading = loading,
        iconContent = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            null
        },
    )
}

@Composable
fun StatePlaceholder(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    iconTint: androidx.compose.ui.graphics.Color = LiferychColors.IconMuted,
) {
    StatePlaceholderContent(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        loading = loading,
        iconContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(40.dp),
            )
        },
    )
}

@Composable
private fun StatePlaceholderContent(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    loading: Boolean,
    iconContent: (@Composable () -> Unit)?,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(LiferychDimens.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        color = LiferychColors.BrandYellowDark,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp),
                    )
                }

                iconContent != null -> iconContent()
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = LiferychTypography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = LiferychTypography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
