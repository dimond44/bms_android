package ru.liferych.bms.ui.screens.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.liferych.bms.ui.components.LiferychCard
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.components.ScreenStateHost
import ru.liferych.bms.ui.components.SectionHeader
import ru.liferych.bms.ui.model.ScreenUiStatus
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.theme.LiferychTypography

/**
 * Charts shell with static fake series. No telemetry implementation.
 */
@Composable
fun ChartsScreen(
    screenStatus: ScreenUiStatus,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LiferychColors.Background,
        topBar = { LiferychTopBar(title = "Графики", onBack = onBack) },
    ) { padding ->
        ScreenStateHost(
            status = screenStatus,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            connectedContent = { ChartsPlaceholderContent() },
        )
    }
}

@Composable
private fun ChartsPlaceholderContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LiferychDimens.ScreenPadding),
    ) {
        LiferychCard {
            SectionHeader(
                title = "SOC (демо)",
                subtitle = "Статичный placeholder · реальная телеметрия позже",
            )
            Spacer(Modifier.height(16.dp))
            FakeLineChart(
                points = listOf(0.62f, 0.65f, 0.68f, 0.70f, 0.74f, 0.76f, 0.78f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LiferychCard {
            Text(
                text = "Напряжение / ток — UI shell",
                style = LiferychTypography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FakeLineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val maxY = 1f
        val minY = 0f
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = stepX * index
            val yNorm = (value - minY) / (maxY - minY)
            val y = size.height - (yNorm * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = LiferychColors.BrandYellowDark,
            style = Stroke(width = 6f, cap = StrokeCap.Round),
        )
        // baseline
        drawLine(
            color = LiferychColors.Divider,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f,
        )
    }
}

@Preview(showBackground = true, name = "ChartsScreenPreview")
@Composable
private fun ChartsScreenPreview() {
    LiferychTheme {
        ChartsScreen(
            screenStatus = ScreenUiStatus.Connected,
            onBack = {},
        )
    }
}
