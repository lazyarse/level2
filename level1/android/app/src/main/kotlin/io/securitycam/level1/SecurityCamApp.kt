package io.securitycam.level1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.securitycam.level1.ui.events.EventsScreen
import io.securitycam.level1.ui.events.EventsViewModel
import io.securitycam.level1.ui.events.HistoryScreen
import io.securitycam.level1.ui.events.HistoryViewModel
import io.securitycam.level1.ui.monitor.MonitorScreen
import io.securitycam.level1.ui.regions.RegionEditorScreen
import io.securitycam.level1.ui.settings.SettingsScreen
import io.securitycam.level1.ui.settings.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Level1Tab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Filled.Videocam),
    Events("Events", Icons.Filled.Event),
    History("History", Icons.Filled.History),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun SecurityCamApp() {
    var tab by remember { mutableStateOf(Level1Tab.Monitor) }
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    var showRegionEditor by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            if (!showRegionEditor) {
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
            if (showRegionEditor) {
                RegionEditorScreen(
                    initialRegions = settingsViewModel.draft.value?.detectionRegions.orEmpty(),
                    onSave = { regions ->
                        settingsViewModel.update { it.copy(detectionRegions = regions) }
                    },
                    onClose = { showRegionEditor = false },
                )
            } else {
                when (tab) {
                    Level1Tab.Monitor -> MonitorScreen()
                    Level1Tab.Events -> EventsScreen(
                        viewModel = viewModel(factory = EventsViewModel.Factory),
                    )
                    Level1Tab.History -> HistoryScreen(
                        viewModel = viewModel(factory = HistoryViewModel.Factory),
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