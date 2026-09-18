package ru.liferych.bms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
 * Compose theme for the client frontend.
 *
 * Pins [LocalDensity.fontScale] to `1f` so system Font size / Display size
 * does not inflate text and chips relative to the reference device.
 * Physical screen density is unchanged.
 *
 * Typography source of truth: [LiferychTypography] (bundled Inter).
 * Dark theme is intentionally not enabled — legacy app is light-only.
 */
@Composable
fun LiferychTheme(
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val ignoreSystemDark = isSystemInDarkTheme()
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = 1f,
        ),
    ) {
        MaterialTheme(
            colorScheme = LiferychLightColorScheme,
            typography = LiferychTypography,
            content = content,
        )
    }
}
