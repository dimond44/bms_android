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
import androidx.core.content.ContextCompat
import ru.liferych.bms.BmsApp
import ru.liferych.bms.ui.app.LiferychFrontendApp
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

/**
 * Compose CLIENT frontend entry.
 *
 * Uses shared AppContainer DalyBmsRepository (real BLE).
 * Legacy MainActivity remains the launcher.
 */
class FrontendActivity : ComponentActivity() {
    private val viewModel: FrontendViewModel by viewModels {
        val repo = (application as BmsApp).container.repository
        FrontendViewModel.Factory(repo)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                viewModel.startScan()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoute = intent?.getStringExtra(EXTRA_START_ROUTE)
        setContent {
            LiferychFrontendApp(
                viewModel = viewModel,
                startRoute = startRoute,
                onRequestBlePermissions = { ensureBlePermissionsAndScan() },
            )
        }
        if (startRoute.isNullOrBlank()) {
            ensureBlePermissionsAndScan()
        }
    }

    private fun ensureBlePermissionsAndScan() {
        val missing = missingBlePermissions()
        if (missing.isEmpty()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

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
    }
}
