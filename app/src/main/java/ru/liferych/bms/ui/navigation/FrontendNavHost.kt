package ru.liferych.bms.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.liferych.bms.ui.components.LiferychBottomBar
import ru.liferych.bms.ui.screens.batteries.BatteriesScreen
import ru.liferych.bms.ui.screens.cells.CellsScreen
import ru.liferych.bms.ui.screens.charts.ChartsScreen
import ru.liferych.bms.ui.screens.dashboard.DashboardScreen
import ru.liferych.bms.ui.screens.journal.JournalScreen
import ru.liferych.bms.ui.screens.journal.activeErrorsFromBattery
import ru.liferych.bms.ui.screens.profile.ProfileScreen
import ru.liferych.bms.ui.screens.qr.QrScreen
import ru.liferych.bms.ui.screens.support.SupportScreen
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

@Composable
fun FrontendNavHost(
    viewModel: FrontendViewModel,
    modifier: Modifier = Modifier,
    startRoute: String? = null,
    onRequestBlePermissions: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val batteryState by viewModel.batteryState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val screenStatus by viewModel.screenStatus.collectAsStateWithLifecycle()
    val batteries by viewModel.batteries.collectAsStateWithLifecycle()
    val selectedBatteryId by viewModel.selectedBatteryId.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val selectedDeviceName by viewModel.selectedDeviceName.collectAsStateWithLifecycle()
    val selected = batteries.firstOrNull { it.id == selectedBatteryId }

    val initialRoute = when (startRoute) {
        FrontendDestination.Cells.route -> FrontendDestination.Cells.route
        FrontendDestination.Dashboard.route -> FrontendDestination.Dashboard.route
        FrontendDestination.Journal.route -> FrontendDestination.Journal.route
        FrontendDestination.QrCode.route -> FrontendDestination.QrCode.route
        FrontendDestination.Support.route -> FrontendDestination.Support.route
        FrontendDestination.Profile.route -> FrontendDestination.Profile.route
        else -> FrontendDestination.Batteries.route
    }

    val showBottomBar = currentRoute in FrontendDestination.batteryTabs.map { it.route } ||
        currentRoute == FrontendDestination.Charts.route ||
        currentRoute == FrontendDestination.Cells.route

    fun navigateToTab(destination: FrontendDestination) {
        navController.navigate(destination.route) {
            popUpTo(FrontendDestination.Dashboard.route) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun backToBatteries() {
        viewModel.clearSelectedBattery()
        viewModel.disconnect()
        navController.navigate(FrontendDestination.Batteries.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = true
            }
            launchSingleTop = true
        }
        onRequestBlePermissions()
    }

    Scaffold(
        modifier = modifier,
        containerColor = LiferychColors.Background,
        bottomBar = {
            if (showBottomBar) {
                LiferychBottomBar(
                    currentRoute = when (currentRoute) {
                        FrontendDestination.Charts.route,
                        FrontendDestination.Cells.route,
                        -> FrontendDestination.Dashboard.route
                        else -> currentRoute
                    },
                    onNavigate = { navigateToTab(it) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = initialRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable(FrontendDestination.Batteries.route) {
                BatteriesScreen(
                    batteries = batteries,
                    connectionState = connectionState,
                    onRefreshScan = onRequestBlePermissions,
                    onBatteryClick = { battery ->
                        val address = battery.address ?: return@BatteriesScreen
                        viewModel.selectBattery(battery.id)
                        viewModel.connect(address)
                        navController.navigate(FrontendDestination.Dashboard.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(FrontendDestination.Dashboard.route) {
                DashboardScreen(
                    batteryName = selectedDeviceName,
                    serialNumber = batteryState.factorySerial?.takeIf { it.isNotBlank() }
                        ?: selected?.serialNumber
                        ?: "--",
                    bmsVersion = batteryState.bmsHwVersion?.takeIf { it.isNotBlank() }
                        ?: selected?.bmsVersion
                        ?: "--",
                    batteryState = batteryState,
                    connectionState = connectionState,
                    screenStatus = screenStatus,
                    onBack = { backToBatteries() },
                    onOpenCharts = {
                        navController.navigate(FrontendDestination.Charts.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(FrontendDestination.Cells.route) {
                CellsScreen(
                    batteryState = batteryState,
                    screenStatus = screenStatus,
                    onBack = {
                        if (!navController.popBackStack()) {
                            backToBatteries()
                        }
                    },
                )
            }
            composable(FrontendDestination.Charts.route) {
                ChartsScreen(
                    screenStatus = screenStatus,
                    onBack = {
                        navController.popBackStack(
                            FrontendDestination.Dashboard.route,
                            inclusive = false,
                        )
                    },
                )
            }
            composable(FrontendDestination.Journal.route) {
                JournalScreen(
                    activeErrors = activeErrorsFromBattery(batteryState.errors),
                    onBack = { backToBatteries() },
                )
            }
            composable(FrontendDestination.QrCode.route) {
                QrScreen(
                    onBack = { backToBatteries() },
                )
            }
            composable(FrontendDestination.Support.route) {
                SupportScreen(
                    onBack = { backToBatteries() },
                    defaultFio = profile.displayName,
                    defaultPhone = profile.phone,
                )
            }
            composable(FrontendDestination.Profile.route) {
                ProfileScreen(
                    profile = profile,
                    onBack = { backToBatteries() },
                )
            }
        }
    }
}
