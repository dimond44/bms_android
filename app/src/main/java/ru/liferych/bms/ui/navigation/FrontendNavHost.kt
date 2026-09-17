package ru.liferych.bms.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.model.BmsConnectionState
import ru.liferych.bms.ui.components.LiferychBottomBar
import ru.liferych.bms.ui.screens.cells.CellsScreen
import ru.liferych.bms.ui.screens.charts.ChartsScreen
import ru.liferych.bms.ui.screens.dashboard.DashboardScreen
import ru.liferych.bms.ui.screens.devicesearch.DeviceSearchScreen
import ru.liferych.bms.ui.screens.diagnostics.DiagnosticsScreen
import ru.liferych.bms.ui.screens.journal.JournalScreen
import ru.liferych.bms.ui.screens.journal.activeErrorsFromBattery
import ru.liferych.bms.ui.screens.mybatteries.MyBatteriesScreen
import ru.liferych.bms.ui.screens.profile.ProfileScreen
import ru.liferych.bms.ui.screens.qr.QrScreen
import ru.liferych.bms.ui.screens.startup.StartupScreen
import ru.liferych.bms.ui.screens.support.SupportScreen
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.viewmodel.ChartsViewModel
import ru.liferych.bms.ui.viewmodel.FrontendViewModel
import ru.liferych.bms.ui.viewmodel.QrViewModel
import ru.liferych.bms.ui.viewmodel.SupportViewModel
import ru.liferych.bms.telemetry.TelemetryBmsUid

/**
 * Compose CLIENT navigation host.
 *
 * Cold start: [FrontendDestination.Startup] → [FrontendDestination.MyBatteries].
 * Add battery: MyBatteries → DeviceScan → connect → MyBatteries.
 * Open battery: MyBatteries → Dashboard (approved screens unchanged).
 *
 * @param viewModel frontend VM (BLE + saved batteries)
 * @param supportViewModel warranty/support VM
 * @param qrViewModel cell QR / Data Matrix VM
 * @param chartsViewModel local Voltage/Current history charts
 * @param startRoute optional deep-link for review (skips Startup when set)
 * @param onRequestBlePermissions Activity BLE permission + scan trigger
 * @param modifier root modifier
 */
@Composable
fun FrontendNavHost(
    viewModel: FrontendViewModel,
    supportViewModel: SupportViewModel,
    qrViewModel: QrViewModel,
    chartsViewModel: ChartsViewModel,
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
    val scanDevices by viewModel.scanDevices.collectAsStateWithLifecycle()
    val savedBatteries by viewModel.savedBatteries.collectAsStateWithLifecycle()
    val cardStatus by viewModel.savedBatteryCardStatus.collectAsStateWithLifecycle()
    val selectedBatteryId by viewModel.selectedBatteryId.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val selectedDeviceName by viewModel.selectedDeviceName.collectAsStateWithLifecycle()
    val selectedFactorySerial by viewModel.selectedFactorySerial.collectAsStateWithLifecycle()
    val pendingOpenDashboard by viewModel.pendingOpenDashboard.collectAsStateWithLifecycle()
    val pendingReturnToMyBatteries by viewModel.pendingReturnToMyBatteries.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val pendingOpenProfileLogin by viewModel.pendingOpenProfileLogin.collectAsStateWithLifecycle()
    val pendingNavigateDeviceScan by viewModel.pendingNavigateDeviceScan.collectAsStateWithLifecycle()

    val deepLinkRoute = when {
        startRoute == FrontendDestination.Startup.route -> FrontendDestination.Startup.route
        startRoute == FrontendDestination.Cells.route -> FrontendDestination.Cells.route
        startRoute == FrontendDestination.Dashboard.route -> FrontendDestination.Dashboard.route
        startRoute == FrontendDestination.Diagnostics.route ||
            startRoute?.startsWith("diagnostics") == true ->
            FrontendDestination.Diagnostics.createRoute(
                FrontendDestination.Diagnostics.SOURCE_DASHBOARD,
            )
        startRoute == FrontendDestination.DeviceScan.route -> FrontendDestination.DeviceScan.route
        startRoute == FrontendDestination.Journal.route -> FrontendDestination.Journal.route
        startRoute == FrontendDestination.QrCode.route -> FrontendDestination.QrCode.route
        startRoute == FrontendDestination.Support.route -> FrontendDestination.Support.route
        startRoute == FrontendDestination.Profile.route -> FrontendDestination.Profile.route
        startRoute == FrontendDestination.MyBatteries.route -> FrontendDestination.MyBatteries.route
        else -> null
    }

    val graphStart = deepLinkRoute ?: FrontendDestination.Startup.route

    val onDiagnosticsRoute = currentRoute?.startsWith("diagnostics") == true ||
        currentRoute == FrontendDestination.Diagnostics.route

    val showBottomBar = currentRoute in FrontendDestination.batteryTabs.map { it.route } ||
        currentRoute == FrontendDestination.Charts.route ||
        currentRoute == FrontendDestination.Cells.route ||
        currentRoute == FrontendDestination.Dashboard.route ||
        onDiagnosticsRoute ||
        currentRoute == FrontendDestination.DeviceScan.route

    fun navigateToTab(destination: FrontendDestination) {
        if (destination == FrontendDestination.Journal &&
            connectionState !is BmsConnectionState.Connected
        ) {
            // Offline: do not open Journal with stale BatteryState.errors.
            return
        }
        // Leaving Profile without completing auth cancels pending AddBattery intent.
        if (destination != FrontendDestination.Profile) {
            viewModel.clearPendingAddBatteryAfterAuth()
        }
        navController.navigate(destination.route) {
            popUpTo(FrontendDestination.MyBatteries.route) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun backToMyBatteries(disconnect: Boolean = false) {
        if (disconnect) {
            viewModel.clearSelectedBattery()
            viewModel.disconnect()
        }
        viewModel.clearPendingAddBatteryAfterAuth()
        navController.navigate(FrontendDestination.MyBatteries.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = true
            }
            launchSingleTop = true
        }
    }

    // After Guest login/register with pending AddBattery → DeviceSearch.
    LaunchedEffect(pendingNavigateDeviceScan) {
        if (!pendingNavigateDeviceScan) return@LaunchedEffect
        viewModel.consumeNavigateDeviceScan()
        if (authState !is AuthState.Authorized) return@LaunchedEffect
        onRequestBlePermissions()
        navController.navigate(FrontendDestination.DeviceScan.route) {
            popUpTo(FrontendDestination.MyBatteries.route) {
                saveState = true
            }
            launchSingleTop = true
        }
    }

    LaunchedEffect(pendingOpenDashboard, connectionState, batteryState, selectedBatteryId) {
        if (!pendingOpenDashboard) return@LaunchedEffect
        when (val c = connectionState) {
            is BmsConnectionState.Error -> {
                // Connect failed — stay on MyBatteries, no stale Dashboard.
                viewModel.consumeOpenDashboard()
            }
            is BmsConnectionState.Connected -> {
                val id = selectedBatteryId ?: return@LaunchedEffect
                if (!c.deviceAddress.equals(id, true)) return@LaunchedEffect
                // Open Dashboard only after live Daly sample (not Connecting alone).
                val hasLive = batteryState.soc != null || batteryState.voltage != null
                if (!hasLive) return@LaunchedEffect
                viewModel.consumeOpenDashboard()
                navController.navigate(FrontendDestination.Dashboard.route) {
                    launchSingleTop = true
                }
            }
            else -> Unit
        }
    }

    LaunchedEffect(pendingReturnToMyBatteries) {
        if (pendingReturnToMyBatteries) {
            viewModel.consumeReturnToMyBatteries()
            backToMyBatteries(disconnect = false)
        }
    }

    // Leave Journal immediately when live BMS is lost (no stale errors).
    LaunchedEffect(connectionState, currentRoute) {
        if (currentRoute == FrontendDestination.Journal.route &&
            connectionState !is BmsConnectionState.Connected
        ) {
            backToMyBatteries(disconnect = false)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = LiferychColors.Background,
        bottomBar = {
            if (showBottomBar) {
                LiferychBottomBar(
                    currentRoute = when {
                        currentRoute == FrontendDestination.Charts.route ||
                            currentRoute == FrontendDestination.Cells.route ||
                            currentRoute == FrontendDestination.Dashboard.route ||
                            currentRoute == FrontendDestination.DeviceScan.route ||
                            onDiagnosticsRoute -> FrontendDestination.MyBatteries.route
                        else -> currentRoute
                    },
                    onNavigate = { navigateToTab(it) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = graphStart,
            modifier = Modifier.padding(padding),
        ) {
            composable(FrontendDestination.Startup.route) {
                // Hold only when deep-linked for UI review screenshots.
                val holdForReview = startRoute == FrontendDestination.Startup.route
                StartupScreen()
                LaunchedEffect(holdForReview) {
                    viewModel.prepareStartup()
                    if (!holdForReview) {
                        navController.navigate(FrontendDestination.MyBatteries.route) {
                            popUpTo(FrontendDestination.Startup.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            composable(FrontendDestination.MyBatteries.route) {
                DisposableEffect(Unit) {
                    onDispose { viewModel.onMyBatteriesDisappear() }
                }
                MyBatteriesScreen(
                    batteries = savedBatteries,
                    cardStatus = cardStatus,
                    canAddBattery = true,
                    onAddBattery = {
                        if (authState !is AuthState.Authorized) {
                            // Guest: Profile → Login, then DeviceSearch after success.
                            viewModel.beginAddBatteryAuthGate()
                            navigateToTab(FrontendDestination.Profile)
                            return@MyBatteriesScreen
                        }
                        onRequestBlePermissions()
                        navController.navigate(FrontendDestination.DeviceScan.route)
                    },
                    onBatteryClick = { battery ->
                        onRequestBlePermissions()
                        viewModel.openSavedBattery(battery)
                    },
                    onRename = { battery, name ->
                        viewModel.renameSavedBattery(battery.address, name)
                    },
                    onDelete = { battery ->
                        viewModel.deleteSavedBattery(battery.address)
                    },
                    onAppear = {
                        viewModel.onMyBatteriesAppear()
                    },
                )
            }
            composable(FrontendDestination.DeviceScan.route) {
                // Navigation guard: Guest cannot stay on Add Battery — send to Login.
                LaunchedEffect(authState) {
                    if (authState !is AuthState.Authorized) {
                        viewModel.stopScan()
                        viewModel.abortAddBatteryFlow()
                        viewModel.beginAddBatteryAuthGate()
                        navigateToTab(FrontendDestination.Profile)
                        return@LaunchedEffect
                    }
                    onRequestBlePermissions()
                }
                val connectingAddress =
                    (connectionState as? BmsConnectionState.Connecting)?.deviceAddress
                DeviceSearchScreen(
                    devices = scanDevices,
                    connectionState = connectionState,
                    connectingAddress = connectingAddress,
                    onRefreshScan = onRequestBlePermissions,
                    onBack = {
                        viewModel.stopScan()
                        viewModel.abortAddBatteryFlow()
                        navController.popBackStack()
                    },
                    onConnect = { device ->
                        val address = device.address ?: return@DeviceSearchScreen
                        viewModel.addDiscoveredBattery(address, device.name)
                    },
                )
            }
            composable(FrontendDestination.Dashboard.route) {
                val overallStatus by viewModel.dashboardOverallStatus.collectAsStateWithLifecycle()
                DashboardScreen(
                    batteryName = selectedDeviceName,
                    serialNumber = selectedFactorySerial,
                    bmsVersion = batteryState.bmsHwVersion?.takeIf { it.isNotBlank() }
                        ?: "--",
                    batteryState = batteryState,
                    connectionState = connectionState,
                    screenStatus = screenStatus,
                    overallStatus = overallStatus,
                    onBack = { backToMyBatteries(disconnect = false) },
                    onOpenCharts = {
                        navController.navigate(FrontendDestination.Charts.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDiagnostics = {
                        viewModel.openDiagnostics()
                        navController.navigate(
                            FrontendDestination.Diagnostics.createRoute(
                                FrontendDestination.Diagnostics.SOURCE_DASHBOARD,
                            ),
                        ) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = FrontendDestination.Diagnostics.route,
                arguments = listOf(
                    androidx.navigation.navArgument(
                        FrontendDestination.Diagnostics.ARG_SOURCE,
                    ) {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = FrontendDestination.Diagnostics.SOURCE_DASHBOARD
                    },
                ),
            ) { entry ->
                val diagnosticsUi by viewModel.diagnosticsUi.collectAsStateWithLifecycle()
                val source = entry.arguments
                    ?.getString(FrontendDestination.Diagnostics.ARG_SOURCE)
                    ?: FrontendDestination.Diagnostics.SOURCE_DASHBOARD
                LaunchedEffect(Unit) {
                    viewModel.openDiagnostics()
                }
                LaunchedEffect(connectionState) {
                    if (connectionState !is BmsConnectionState.Connected) {
                        viewModel.openDiagnostics()
                    }
                }
                fun leaveDiagnostics() {
                    when (source) {
                        FrontendDestination.Diagnostics.SOURCE_SUPPORT -> {
                            if (!navController.popBackStack(
                                    FrontendDestination.Support.route,
                                    inclusive = false,
                                )
                            ) {
                                navController.navigate(FrontendDestination.Support.route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                        else -> {
                            if (!navController.popBackStack(
                                    FrontendDestination.Dashboard.route,
                                    inclusive = false,
                                )
                            ) {
                                navController.navigate(FrontendDestination.Dashboard.route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }
                DiagnosticsScreen(
                    state = diagnosticsUi,
                    onBack = { leaveDiagnostics() },
                    onOk = { leaveDiagnostics() },
                    onFix = { viewModel.applyConfigFix() },
                )
            }
            composable(FrontendDestination.Cells.route) {
                CellsScreen(
                    batteryState = batteryState,
                    screenStatus = screenStatus,
                    onBack = {
                        if (!navController.popBackStack()) {
                            backToMyBatteries()
                        }
                    },
                )
            }
            composable(FrontendDestination.Charts.route) {
                val address = selectedBatteryId.orEmpty()
                val bleName = savedBatteries
                    .firstOrNull { it.address.equals(address, ignoreCase = true) }
                    ?.bluetoothName
                    .orEmpty()
                val chartsUid = TelemetryBmsUid.resolve(address, bleName)
                ChartsScreen(
                    bmsUid = chartsUid,
                    chartsViewModel = chartsViewModel,
                    onBack = {
                        navController.popBackStack(
                            FrontendDestination.Dashboard.route,
                            inclusive = false,
                        )
                    },
                )
            }
            composable(FrontendDestination.Journal.route) {
                val liveErrors = if (connectionState is BmsConnectionState.Connected) {
                    activeErrorsFromBattery(batteryState.errors)
                } else {
                    emptyList()
                }
                JournalScreen(
                    activeErrors = liveErrors,
                    onBack = { backToMyBatteries() },
                )
            }
            composable(FrontendDestination.QrCode.route) {
                QrScreen(
                    onBack = { backToMyBatteries() },
                    viewModel = qrViewModel,
                )
            }
            composable(FrontendDestination.Support.route) {
                val supportPhase by supportViewModel.phase.collectAsStateWithLifecycle()
                val supportAttachments by supportViewModel.attachments.collectAsStateWithLifecycle()
                val supportSubmitting by supportViewModel.submitting.collectAsStateWithLifecycle()
                SupportScreen(
                    onBack = {
                        // Support root → Dashboard («Главная») when live BMS exists.
                        val live = connectionState is BmsConnectionState.Connected &&
                            !selectedBatteryId.isNullOrBlank()
                        if (live) {
                            navController.navigate(FrontendDestination.Dashboard.route) {
                                popUpTo(FrontendDestination.MyBatteries.route) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            backToMyBatteries()
                        }
                    },
                    phase = supportPhase,
                    attachments = supportAttachments,
                    submitting = supportSubmitting,
                    onOpenHome = { supportViewModel.openHome() },
                    onOpenNew = { supportViewModel.openNewRequest() },
                    onOpenList = { supportViewModel.openRequestList() },
                    onOpenDetails = { supportViewModel.openDetails(it) },
                    onNewFormChange = { supportViewModel.updateNewForm(it) },
                    onDetailsFormChange = { supportViewModel.updateDetailsForm(it) },
                    onSubmit = { supportViewModel.submitCurrent() },
                    onAddAttachment = { supportViewModel.addAttachment(it) },
                    onRemoveAttachment = { supportViewModel.removeAttachment(it) },
                    onDiagnostics = {
                        viewModel.openDiagnostics()
                        navController.navigate(
                            FrontendDestination.Diagnostics.createRoute(
                                FrontendDestination.Diagnostics.SOURCE_SUPPORT,
                            ),
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onPreviousPage = { supportViewModel.goToPreviousPage() },
                    onNextPage = { supportViewModel.goToNextPage() },
                )
            }
            composable(FrontendDestination.Profile.route) {
                val feedback by viewModel.profileFeedback.collectAsStateWithLifecycle()
                val avatarUi by viewModel.profileAvatarUi.collectAsStateWithLifecycle()
                ProfileScreen(
                    profile = profile,
                    authState = authState,
                    feedback = feedback,
                    avatarUi = avatarUi,
                    openLogin = pendingOpenProfileLogin,
                    onOpenLoginConsumed = { viewModel.consumeOpenProfileLogin() },
                    onCancelAuthFlow = { viewModel.clearPendingAddBatteryAfterAuth() },
                    onBack = {
                        viewModel.clearPendingAddBatteryAfterAuth()
                        backToMyBatteries()
                    },
                    onLoginSubmit = { phone -> viewModel.login(phone) },
                    onRegisterSubmit = { name, phone -> viewModel.register(name, phone) },
                    onSaveProfile = { name, phone, email, birth ->
                        viewModel.saveProfile(name, phone, email, birth)
                    },
                    onLogout = { viewModel.logout() },
                    onClearFeedback = { viewModel.clearProfileFeedback() },
                    onAvatarPicked = { uri -> viewModel.updateAvatarFromUri(uri) },
                    onClearAvatarMessage = { viewModel.clearProfileAvatarMessage() },
                )
            }
        }
    }
}
