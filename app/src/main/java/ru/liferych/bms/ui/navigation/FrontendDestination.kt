package ru.liferych.bms.ui.navigation

/**
 * Compose navigation routes for the new client frontend.
 * Bottom tabs follow legacy CLIENT: Главная / Журнал / QR / Поддержка / Профиль.
 */
sealed class FrontendDestination(val route: String) {
    data object Batteries : FrontendDestination("batteries")

    data object Dashboard : FrontendDestination("dashboard")

    data object Cells : FrontendDestination("cells")

    data object Charts : FrontendDestination("charts")

    data object Journal : FrontendDestination("journal")

    data object QrCode : FrontendDestination("qr")

    data object Support : FrontendDestination("support")

    data object Profile : FrontendDestination("profile")

    companion object {
        val batteryTabs = listOf(Dashboard, Journal, QrCode, Support, Profile)
    }
}
