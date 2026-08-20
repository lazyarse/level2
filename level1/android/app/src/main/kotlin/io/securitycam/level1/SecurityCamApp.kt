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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.securitycam.level1.ui.monitor.MonitorScreen

enum class Level1Tab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Filled.Videocam),
    Events("Events", Icons.Filled.Event),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun SecurityCamApp() {
    var tab by remember { mutableStateOf(Level1Tab.Monitor) }
    Scaffold(
        bottomBar = {
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
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                Level1Tab.Monitor -> MonitorScreen()
                Level1Tab.Events -> Text(
                    text = "Events — Phase 5",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Level1Tab.Settings -> Text(
                    text = "Settings — Phase 5",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}