package ru.liferych.bms.ui.components

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.R
import ru.liferych.bms.ui.navigation.FrontendDestination
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens

data class BottomNavItem(
    val destination: FrontendDestination,
    val label: String,
    @DrawableRes val iconRes: Int,
)

/** Legacy CLIENT bottom nav with Liferych outline icons. */
val BatteryBottomNavItems = listOf(
    BottomNavItem(FrontendDestination.MyBatteries, "Главная", R.drawable.ic_liferych_home),
    BottomNavItem(FrontendDestination.Journal, "Журнал", R.drawable.ic_liferych_journal),
    BottomNavItem(FrontendDestination.QrCode, "QR-код", R.drawable.ic_liferych_qr),
    BottomNavItem(FrontendDestination.Support, "Поддержка", R.drawable.ic_liferych_support),
    BottomNavItem(FrontendDestination.Profile, "Профиль", R.drawable.ic_liferych_profile),
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
                        painter = painterResource(item.iconRes),
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
