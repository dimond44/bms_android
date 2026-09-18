package ru.liferych.bms.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R

private val LiferychFontFamily = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal),
)

/**
 * Typography closer to legacy Inter weights used on CLIENT dashboard.
 *
 * App typography source of truth for Compose BMS UI (title/body/label/button).
 * Glyph metrics use bundled Inter ([R.font.inter]); system fontScale is pinned
 * in [LiferychTheme] so OEM Font size settings do not change layout scale.
 */
val LiferychTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        color = LiferychColors.TextPrimary,
    ),
    headlineLarge = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = LiferychColors.TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        color = LiferychColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        color = LiferychColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = LiferychColors.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = LiferychColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = LiferychColors.TextSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = LiferychColors.TextSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        color = LiferychColors.TextSecondary,
    ),
    labelMedium = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = LiferychColors.TextSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = LiferychFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        color = LiferychColors.TextSecondary,
    ),
)
