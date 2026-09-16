package ru.liferych.bms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LiferychLightColorScheme = lightColorScheme(
    primary = LiferychColors.BrandYellow,
    onPrimary = LiferychColors.TextOnAccent,
    primaryContainer = LiferychColors.BrandYellowSoft,
    onPrimaryContainer = LiferychColors.TextPrimary,
    secondary = LiferychColors.TextSecondary,
    onSecondary = Color.White,
    background = LiferychColors.Background,
    onBackground = LiferychColors.TextPrimary,
    surface = LiferychColors.SurfaceElevated,
    onSurface = LiferychColors.TextPrimary,
    surfaceVariant = LiferychColors.Surface,
    onSurfaceVariant = LiferychColors.TextSecondary,
    outline = LiferychColors.Divider,
    error = LiferychColors.Error,
    onError = Color.White,
)

/**
 * Compose theme for the new client frontend.
 * Dark theme is intentionally not enabled yet — legacy app is light-only.
 */
@Composable
fun LiferychTheme(
    content: @Composable () -> Unit,
) {
    // Keep light brand look regardless of system dark mode for stage-1 frontend.
    @Suppress("UNUSED_VARIABLE")
    val ignoreSystemDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LiferychLightColorScheme,
        typography = LiferychTypography,
        content = content,
    )
}
