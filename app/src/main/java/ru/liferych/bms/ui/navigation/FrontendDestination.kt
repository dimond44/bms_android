package ru.liferych.bms.ui.navigation

/**
 * Compose navigation routes for the new client frontend.
 * Bottom tabs follow legacy CLIENT: Главная / Журнал / QR / Поддержка / Профиль.
 *
 * Главная = [MyBatteries] (saved list), not Dashboard.
 * Dashboard opens only after selecting a saved battery.
 */
sealed class FrontendDestination(val route: String) {
    /** Cold-start gate: logo + init spinner (no bottom bar). */
    data object Startup : FrontendDestination("startup")

    /** Legacy «Мои батареи» — root of Главная tab. */
    data object MyBatteries : FrontendDestination("my_batteries")

    /** BLE scan to add a new BMS (legacy search / «ДОБАВИТЬ БАТАРЕЮ»). */
    data object DeviceScan : FrontendDestination("device_scan")

    data object Dashboard : FrontendDestination("dashboard")

    /**
     * Shared config diagnostics (Dashboard / Support entry).
     * Route pattern: `diagnostics/{source}` where source is `dashboard` or `support`.
     */
    data object Diagnostics : FrontendDestination("diagnostics/{source}") {
        const val ARG_SOURCE = "source"
        const val SOURCE_DASHBOARD = "dashboard"
        const val SOURCE_SUPPORT = "support"

        /** Builds concrete route for [source]. */
        fun createRoute(source: String): String = "diagnostics/$source"
    }

    data object Cells : FrontendDestination("cells")

    data object Charts : FrontendDestination("charts")

    data object Journal : FrontendDestination("journal")

    data object QrCode : FrontendDestination("qr")

    data object Support : FrontendDestination("support")

    data object Profile : FrontendDestination("profile")

    companion object {
        /** Bottom bar destinations. Dashboard is NOT a tab. */
        val batteryTabs = listOf(MyBatteries, Journal, QrCode, Support, Profile)
    }
}
