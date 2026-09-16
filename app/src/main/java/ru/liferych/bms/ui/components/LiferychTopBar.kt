package ru.liferych.bms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.ui.theme.LiferychColors

/**
 * Legacy CLIENT header: back + logo + «ЛИФЕРЫЧ».
 * Fixed content height (no statusBarsPadding here — parent owns insets).
 */
@Composable
fun LiferychBrandHeader(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LiferychColors.Background)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Назад",
                tint = LiferychColors.TextPrimary,
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.width(8.dp))
        }
        Image(
            painter = painterResource(R.drawable.liferych_logo),
            contentDescription = "Логотип Лиферыч",
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "ЛИФЕРЫЧ",
            color = LiferychColors.TextPrimary,
            fontSize = if (onBack != null) 20.sp else 22.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
fun LiferychTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    useBrandHeader: Boolean = false,
) {
    if (useBrandHeader) {
        LiferychBrandHeader(modifier = modifier, onBack = onBack)
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(LiferychColors.Background)
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Назад",
                    tint = LiferychColors.TextPrimary,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onBack),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = LiferychColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
