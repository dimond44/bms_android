package ru.liferych.bms.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.liferych.bms.ui.navigation.FrontendNavHost
import ru.liferych.bms.ui.theme.LiferychTheme
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

/**
 * Root composable for the new client frontend (fake repository only).
 *
 * @param startRoute optional deep-link route for UI review (e.g. "cells").
 */
@Composable
fun LiferychFrontendApp(
    viewModel: FrontendViewModel,
    modifier: Modifier = Modifier,
    startRoute: String? = null,
) {
    LiferychTheme {
        FrontendNavHost(
            viewModel = viewModel,
            modifier = modifier,
            startRoute = startRoute,
        )
    }
}
