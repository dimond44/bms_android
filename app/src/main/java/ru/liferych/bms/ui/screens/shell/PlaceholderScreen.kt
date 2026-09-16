package ru.liferych.bms.ui.screens.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Temporary shell for legacy bottom-nav destinations not redesigned yet.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LiferychColors.Background,
        topBar = { LiferychTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = message, style = LiferychTypography.bodyMedium)
        }
    }
}
