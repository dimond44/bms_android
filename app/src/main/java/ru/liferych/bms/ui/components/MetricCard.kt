package ru.liferych.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Equal-height telemetry tile for 2-column Dashboard grid.
 */
@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LiferychDimens.MetricCardMinHeight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(LiferychDimens.CardRadius))
            .background(LiferychColors.Surface)
            .padding(LiferychDimens.CardPadding),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space8),
        ) {
            Box(
                modifier = Modifier
                    .size(LiferychDimens.MetricIconWell)
                    .background(LiferychColors.BrandYellowSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LiferychColors.BrandYellowDark,
                    modifier = Modifier.size(LiferychDimens.MetricIconSize),
                )
            }
            Text(
                text = title,
                style = LiferychTypography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(LiferychDimens.Space12))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(LiferychDimens.Space4),
        ) {
            Text(
                text = value,
                style = LiferychTypography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = unit,
                style = LiferychTypography.labelLarge,
                color = LiferychColors.TextSecondary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = LiferychTypography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(LiferychDimens.Space4))
            Text(text = subtitle, style = LiferychTypography.bodySmall)
        }
    }
}

/**
 * Two equal columns for metric tiles.
 */
@Composable
fun MetricGridRow(
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(LiferychDimens.MetricGap),
    ) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}
