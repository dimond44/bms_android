package ru.liferych.bms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.ui.theme.LiferychColors
import androidx.compose.ui.graphics.drawscope.Stroke
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTypography

@Composable
fun InfoOneLineTile(
    label: String,
    value: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    // Fixed height: value/title must never stretch the Dashboard info row.
    Column(
        modifier = modifier
            .height(LiferychDimens.InfoTileHeight)
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = LiferychColors.IconDefault,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = LiferychColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        AutoSizeSingleLineText(
            text = value,
            color = LiferychColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxFontSize = 14.sp,
            minFontSize = 10.sp,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ClassicMetricTile(
    label: String,
    value: String,
    @DrawableRes iconRes: Int?,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = LiferychColors.TextPrimary,
    iconTint: androidx.compose.ui.graphics.Color = LiferychColors.IconDefault,
    glyph: String? = null,
    glyphColor: androidx.compose.ui.graphics.Color = LiferychColors.SuccessAlt,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LiferychDimens.MetricCardMinHeight)
            .fillMaxHeight()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = LiferychColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    iconRes != null -> {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    glyph != null -> {
                        Text(
                            text = glyph,
                            color = glyphColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
            )
        }
    }
}

@Composable
fun CapacityInnerTile(
    label: String,
    value: String,
    @DrawableRes iconRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.InnerCardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LiferychColors.SurfaceMuted)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(10.dp),
    ) {
        Text(
            text = label,
            style = LiferychTypography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = LiferychColors.IconDefault,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = value,
                color = LiferychColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
            )
        }
    }
}

/**
 * Compact read-only MOS switch matching legacy visual mass (not Material Switch).
 */
@Composable
fun CompactMosSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackW = 40.dp
    val trackH = 24.dp
    val thumb = 18.dp
    Canvas(
        modifier = modifier.size(trackW, trackH),
    ) {
        val trackColor = if (checked) {
            LiferychColors.Success.copy(alpha = 0.45f)
        } else {
            LiferychColors.Divider
        }
        val thumbColor = if (checked) LiferychColors.Success else LiferychColors.Surface
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius),
            size = size,
        )
        val thumbR = thumb.toPx() / 2f
        val cx = if (checked) {
            size.width - thumbR - 3.dp.toPx()
        } else {
            thumbR + 3.dp.toPx()
        }
        drawCircle(
            color = thumbColor,
            radius = thumbR,
            center = Offset(cx, size.height / 2f),
        )
        if (!checked) {
            drawCircle(
                color = LiferychColors.Border,
                radius = thumbR,
                center = Offset(cx, size.height / 2f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
fun MosToggleTile(
    label: String,
    enabled: Boolean?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LiferychDimens.CardRadius)
    val checked = enabled == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LiferychDimens.MetricCardMinHeight)
            .fillMaxHeight()
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = LiferychColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        CompactMosSwitch(checked = checked)
    }
}

@Composable
fun MetricPairRow(
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

@Composable
fun ChartLinkRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_liferych_chart),
            contentDescription = null,
            tint = LiferychColors.ChartLink,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "График",
            color = LiferychColors.ChartLink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
