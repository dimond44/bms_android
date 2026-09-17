package ru.liferych.bms.ui.screens.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.liferych.bms.R
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * In-app startup / BMS init gate for Compose CLIENT.
 *
 * Composition mirrors legacy [ru.liferych.bms.MainActivity.showLoadingScreen]:
 * centered logo → yellow indeterminate progress → status label.
 * Visual tokens come from the approved Compose design system (no bottom bar).
 *
 * @param statusText legacy-style status under the spinner
 * @param modifier layout modifier
 */
@Composable
fun StartupScreen(
    modifier: Modifier = Modifier,
    statusText: String = "Идёт инициализация BMS",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.liferych_logo),
            contentDescription = "ЛИФЕРЫЧ",
            modifier = Modifier
                .width(188.dp)
                .height(179.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(30.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = LiferychColors.BrandYellow,
            trackColor = LiferychColors.BrandYellowSoft,
            strokeWidth = 4.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = statusText,
            style = LiferychTypography.bodyMedium,
            color = LiferychColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, name = "StartupScreenPreview")
@Composable
private fun StartupScreenPreview() {
    LiferychTheme {
        StartupScreen()
    }
}
