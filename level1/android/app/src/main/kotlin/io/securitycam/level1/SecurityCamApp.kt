package io.securitycam.level1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.securitycam.level1.ui.events.EventsScreen
import io.securitycam.level1.ui.events.EventsViewModel
import io.securitycam.level1.ui.monitor.MonitorScreen
import io.securitycam.level1.ui.regions.RegionEditorScreen
import io.securitycam.level1.ui.settings.FaceEnrollmentScreen
import io.securitycam.level1.ui.settings.SettingsScreen
import io.securitycam.level1.ui.settings.SettingsViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Level1Tab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Filled.Videocam),
    Events("Events", Icons.Filled.Event),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * Injectable view-model factories default to the production singletons so
 * Robolectric shell tests can swap in fakes (Keystore and Room are not
 * available on the JVM).
 */
@Composable
fun SecurityCamApp(
    eventsFactory: ViewModelProvider.Factory = EventsViewModel.Factory,
    settingsFactory: ViewModelProvider.Factory = SettingsViewModel.Factory,
) {
    var tab by remember { mutableStateOf(Level1Tab.Monitor) }
    val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory)
    var showRegionEditor by remember { mutableStateOf(false) }
    // Full-screen capture page while a face enrollment is in flight; it is
    // dismissed automatically when the enrollment finishes (label → null).
    val enrollingLabel by settingsViewModel.enrollingLabel.collectAsState()
    val enrollmentActive = enrollingLabel != null
    val enrollmentSessionLocal by settingsViewModel.enrollmentSessionLocal.collectAsState()
    Scaffold(
        bottomBar = {
            if (!showRegionEditor && !enrollmentActive) {
                NavigationBar {
                    Level1Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (enrollmentActive) {
                FaceEnrollmentScreen(
                    label = enrollingLabel.orEmpty(),
                    onCancel = { settingsViewModel.cancelEnrollment() },
                    onFlipCamera = { settingsViewModel.flipEnrollmentCamera() },
                    canFlipCamera = enrollmentSessionLocal,
                )
            } else if (showRegionEditor) {
                // Live camera behind the editor so regions land on real
                // features; session ownership is released on any exit path.
                DisposableEffect(Unit) {
                    settingsViewModel.beginRegionPreview()
                    onDispose { settingsViewModel.endRegionPreview() }
                }
                // Letterbox mapping needs the analysis-frame aspect so drawn
                // regions match detector coordinates exactly.
                val analysisDims = io.securitycam.level1.core.AnalysisResolution.size(
                    settingsViewModel.draft.value?.analysisResolution
                        ?: io.securitycam.level1.core.AnalysisResolution.balanced,
                )
                RegionEditorScreen(
                    initialRegions = settingsViewModel.draft.value?.detectionRegions.orEmpty(),
                    initialExclusions = settingsViewModel.draft.value?.exclusionRegions.orEmpty(),
                    onSave = { regions, exclusions ->
                        settingsViewModel.update {
                            it.copy(detectionRegions = regions, exclusionRegions = exclusions)
                        }
                    },
                    onClose = { showRegionEditor = false },
                    frameWidth = analysisDims.first,
                    frameHeight = analysisDims.second,
                )
            } else {
                when (tab) {
                    Level1Tab.Monitor -> MonitorScreen()
                    Level1Tab.Events -> EventsScreen(
                        viewModel = viewModel(factory = eventsFactory),
                    )
                    Level1Tab.Settings -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onOpenRegionEditor = { showRegionEditor = true },
                    )
                }
            }
        }
    }
}