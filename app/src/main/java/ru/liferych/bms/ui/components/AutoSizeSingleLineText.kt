package ru.liferych.bms.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Single-line text that shrinks [fontSize] to fit the available width.
 *
 * Does not change app-wide typography or [LocalDensity.fontScale].
 * Uses [rememberTextMeasurer] once per (text, width, size bounds) — no layout loops.
 *
 * @param text value to show
 * @param modifier layout modifier (usually [Modifier.fillMaxWidth])
 * @param color text color
 * @param fontWeight weight
 * @param maxFontSize starting size
 * @param minFontSize floor size; beyond that Ellipsis applies
 * @param letterSpacing optional tracking
 * @param style base style (font family from [LiferychTypography] by default)
 */
@Composable
fun AutoSizeSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontWeight: FontWeight = FontWeight.SemiBold,
    maxFontSize: TextUnit = 14.sp,
    minFontSize: TextUnit = 10.sp,
    letterSpacing: TextUnit = (-0.2).sp,
    style: TextStyle = LiferychTypography.bodyLarge,
) {
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val resolvedSize = remember(
            text,
            maxWidthPx,
            maxFontSize,
            minFontSize,
            letterSpacing,
            fontWeight,
            style,
            density.density,
            density.fontScale,
        ) {
            if (maxWidthPx == Int.MAX_VALUE || maxWidthPx <= 0) {
                return@remember maxFontSize
            }
            val maxSp = maxFontSize.value
            val minSp = minFontSize.value.coerceAtMost(maxSp)
            var candidate = maxSp
            while (candidate > minSp + 0.01f) {
                val layout = textMeasurer.measure(
                    text = text,
                    style = style.copy(
                        fontSize = candidate.sp,
                        fontWeight = fontWeight,
                        letterSpacing = letterSpacing,
                        color = color,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                if (layout.size.width <= maxWidthPx) {
                    return@remember candidate.sp
                }
                candidate = (candidate - 0.5f).coerceAtLeast(minSp)
            }
            minSp.sp
        }
        Text(
            text = text,
            color = color,
            fontSize = resolvedSize,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
