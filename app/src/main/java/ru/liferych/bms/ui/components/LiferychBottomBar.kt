package ru.liferych.bms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.ui.navigation.FrontendDestination
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens

data class BottomNavItem(
    val destination: FrontendDestination,
    val label: String,
    val icon: ImageVector,
)

/** Legacy CLIENT bottom nav with unified Outlined vector icons (no emoji). */
val BatteryBottomNavItems = listOf(
    BottomNavItem(FrontendDestination.Dashboard, "Главная", Icons.Outlined.Home),
    BottomNavItem(FrontendDestination.Journal, "Журнал", Icons.Outlined.ListAlt),
    BottomNavItem(FrontendDestination.QrCode, "QR-код", Icons.Outlined.QrCode2),
    BottomNavItem(FrontendDestination.Support, "Поддержка", Icons.Outlined.Phone),
    BottomNavItem(FrontendDestination.Profile, "Профиль", Icons.Outlined.Person),
)

@Composable
fun LiferychBottomBar(
    currentRoute: String?,
    onNavigate: (FrontendDestination) -> Unit,
    modifier: Modifier = Modifier,
    items: List<BottomNavItem> = BatteryBottomNavItems,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider(color = LiferychColors.Border, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LiferychDimens.BottomBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.destination.route
                val color = if (selected) {
                    LiferychColors.BottomBarSelected
                } else {
                    LiferychColors.BottomBarUnselected
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(LiferychDimens.BottomBarHeight)
                        .clickable { onNavigate(item.destination) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
