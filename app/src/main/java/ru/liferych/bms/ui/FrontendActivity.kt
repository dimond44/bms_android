package ru.liferych.bms.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.liferych.bms.ui.app.LiferychFrontendApp
import ru.liferych.bms.ui.fake.FakeBmsRepository
import ru.liferych.bms.ui.viewmodel.FrontendViewModel

/**
 * Standalone entry for the new Compose client frontend.
 *
 * Not a launcher. Uses [FakeBmsRepository] only — no real BLE / DalyBmsRepository.
 * Legacy [ru.liferych.bms.MainActivity] remains the production launcher.
 *
 * Optional intent extra `start_route` (e.g. `cells`) for UI review screenshots.
 */
class FrontendActivity : ComponentActivity() {
    private val fakeRepository = FakeBmsRepository()

    private val viewModel: FrontendViewModel by viewModels {
        FrontendViewModel.Factory(fakeRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoute = intent?.getStringExtra(EXTRA_START_ROUTE)
        setContent {
            LiferychFrontendApp(
                viewModel = viewModel,
                startRoute = startRoute,
            )
        }
    }

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
    }
}
