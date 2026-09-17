package ru.liferych.bms.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.liferych.bms.ui.navigation.FrontendNavHost
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.viewmodel.ChartsViewModel
import ru.liferych.bms.ui.viewmodel.FrontendViewModel
import ru.liferych.bms.ui.viewmodel.QrViewModel
import ru.liferych.bms.ui.viewmodel.SupportViewModel

/**
 * Root composable for the new client frontend.
 *
 * @param startRoute optional deep-link route for UI review (e.g. "cells").
 * @param onRequestBlePermissions Activity-owned BLE permission + scan trigger.
 */
@Composable
fun LiferychFrontendApp(
    viewModel: FrontendViewModel,
    supportViewModel: SupportViewModel,
    qrViewModel: QrViewModel,
    chartsViewModel: ChartsViewModel,
    modifier: Modifier = Modifier,
    startRoute: String? = null,
    onRequestBlePermissions: () -> Unit = {},
) {
    LiferychTheme {
        FrontendNavHost(
            viewModel = viewModel,
            supportViewModel = supportViewModel,
            qrViewModel = qrViewModel,
            chartsViewModel = chartsViewModel,
            modifier = modifier,
            startRoute = startRoute,
            onRequestBlePermissions = onRequestBlePermissions,
        )
    }
}
