package ru.liferych.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens

/**
 * Legacy-style white tile: thin border, soft radius, optional light elevation.
 */
@Composable
fun LiferychCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    padding: Dp = LiferychDimens.CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (elevated) {
                    Modifier.shadow(
                        elevation = LiferychDimens.CardElevation,
                        shape = shape,
                        clip = false,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(padding),
        content = content,
    )
}
