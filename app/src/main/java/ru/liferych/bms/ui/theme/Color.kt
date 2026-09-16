package ru.liferych.bms.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Exact legacy MainActivity palette (CLIENT).
 * Brand yellow is named `red` in legacy code: Color.rgb(255, 196, 0).
 */
object LiferychColors {
    val BrandYellow = Color(0xFFFFC400)
    val BrandYellowDark = Color(0xFFFFB700)
    val BrandYellowSoft = Color(0xFFFFF8DA)

    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF8F9FB)
    val Divider = Color(0xFFDFE5EB)
    val Border = Color(0xFFDFE5EB)

    val TextPrimary = Color(0xFF101114)
    val TextSecondary = Color(0xFF6F7781)
    val TextTertiary = Color(0xFF6F7781)
    val TextOnAccent = Color(0xFF101114)

    val Success = Color(0xFF2DB345)
    val SuccessAlt = Color(0xFF1FB35A)
    val SuccessSoft = Color(0xFFEDFAF2)
    val Warning = Color(0xFFD7A023)
    val WarningSoft = Color(0xFFFFF3E0)
    val Error = Color(0xFFD24646)
    val ErrorSoft = Color(0xFFFFEBEE)

    val ChartLink = Color(0xFF1976D2)
    val IconMuted = Color(0xFF465569)
    val IconDefault = Color(0xFF465569)
    val IconOnAccent = Color(0xFF101114)

    val SocTrack = Color(0xFFE1E1E1)
    val BottomBarSelected = BrandYellowDark
    val BottomBarUnselected = TextSecondary
}
