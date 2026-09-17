package ru.liferych.bms.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.liferych.bms.BmsApp
import ru.liferych.bms.ui.app.LiferychFrontendApp
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychTypography
import ru.liferych.bms.ui.viewmodel.ChartsViewModel
import ru.liferych.bms.ui.viewmodel.FrontendViewModel
import ru.liferych.bms.ui.viewmodel.QrViewModel
import ru.liferych.bms.ui.viewmodel.SupportViewModel

/**
 * Compose CLIENT frontend entry.
 *
 * Uses shared AppContainer DalyBmsRepository + SavedBatteriesStore.
 * System splash: white + brand icon ([Theme.Liferych.Splash]).
 * Legacy MainActivity remains the launcher.
 */
class FrontendActivity : ComponentActivity() {
    private val appContainer get() = (application as BmsApp).container

    private val viewModel: FrontendViewModel by viewModels {
        val container = appContainer
        FrontendViewModel.Factory(
            container.repository,
            container.savedBatteriesStore,
            container.bmsIdentityStore,
            container.authRepository,
            ru.liferych.bms.data.auth.ProfileAvatarEncoder(applicationContext),
            container.configDiagnosticsRepository,
            container.clientTemplateFixWriter,
        )
    }

    private val supportViewModel: SupportViewModel by viewModels {
        val container = appContainer
        SupportViewModel.Factory(
            container.supportRepository,
            container.authRepository,
            container.repository,
            container.savedBatteriesStore,
            container.bmsIdentityStore,
            container.supportMediaEncoder,
        )
    }

    private val qrViewModel: QrViewModel by viewModels { QrViewModel.Factory() }

    private val chartsViewModel: ChartsViewModel by viewModels {
        ChartsViewModel.Factory(applicationContext)
    }

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                viewModel.startScan()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            if (granted) {
                appContainer.locationCoordinator.onPermissionGranted()
            } else {
                appContainer.locationCoordinator.onPermissionDeclined()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoute = intent?.getStringExtra(EXTRA_START_ROUTE)
        val batteryAddress = intent?.getStringExtra(EXTRA_BATTERY_ADDRESS)?.trim().orEmpty()
        if (batteryAddress.isNotBlank()) {
            viewModel.selectBattery(batteryAddress)
            if (intent?.getBooleanExtra(EXTRA_OPEN_DASHBOARD, false) == true) {
                val saved = viewModel.savedBatteries.value.firstOrNull {
                    it.address.equals(batteryAddress, true)
                }
                if (saved != null) {
                    viewModel.openSavedBattery(saved)
                } else {
                    viewModel.selectBattery(batteryAddress)
                }
            }
        }
        setContent {
            val showLocationPrompt by appContainer.locationCoordinator.permissionPrompt
                .collectAsStateWithLifecycle()
            LiferychFrontendApp(
                viewModel = viewModel,
                supportViewModel = supportViewModel,
                qrViewModel = qrViewModel,
                chartsViewModel = chartsViewModel,
                startRoute = startRoute,
                onRequestBlePermissions = { ensureBlePermissionsAndScan() },
            )
            if (showLocationPrompt) {
                AlertDialog(
                    onDismissRequest = {
                        appContainer.locationCoordinator.dismissPermissionPrompt()
                    },
                    title = {
                        Text(
                            text = "Местоположение АКБ",
                            style = LiferychTypography.titleMedium,
                            color = LiferychColors.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            text = "Разрешите доступ к геопозиции, чтобы отображать " +
                                "местоположение аккумулятора в личном кабинете.",
                            style = LiferychTypography.bodyMedium,
                            color = LiferychColors.TextSecondary,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                appContainer.locationCoordinator.dismissPermissionPrompt()
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            },
                        ) {
                            Text("Разрешить", color = LiferychColors.BrandYellowDark)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                appContainer.locationCoordinator.onPermissionDeclined()
                            },
                        ) {
                            Text("Не сейчас", color = Color(0xFF6F7781))
                        }
                    },
                    containerColor = Color.White,
                )
            }
        }
    }

    /**
     * Requests missing BLE permissions, then starts scan when granted.
     *
     * Side effect: may show the system permission dialog.
     */
    private fun ensureBlePermissionsAndScan() {
        val missing = missingBlePermissions()
        if (missing.isEmpty()) {
            viewModel.startScan()
        } else {
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * @return BLE-related permissions not yet granted for this API level
     */
    private fun missingBlePermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
        /** Optional BLE MAC to pre-select for Dashboard serial/metadata. */
        const val EXTRA_BATTERY_ADDRESS = "battery_address"
        /** When true with [EXTRA_BATTERY_ADDRESS], opens saved battery → Dashboard. */
        const val EXTRA_OPEN_DASHBOARD = "open_dashboard"
    }
}
