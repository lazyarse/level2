package io.securitycam.level1.ui.monitor

import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.securitycam.level1.camera_service.MonitoringServiceController
import io.securitycam.level1.monitor.MonitorState
import io.securitycam.level1.monitor.MonitorViewModel

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val previewActive by viewModel.previewActive.collectAsStateWithLifecycle()
    val cameraName by viewModel.cameraName.collectAsStateWithLifecycle()
    val detectionRegions by viewModel.detectionRegions.collectAsStateWithLifecycle()
    val zoomRatio by MonitoringServiceController.zoomRatio().collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (viewModel.hasCorePermissions()) viewModel.start() else viewModel.onPermissionsDenied()
    }

    val displayRotation = LocalContext.current.display?.rotation ?: Surface.ROTATION_0
    val rotationDegrees = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    var showRegions by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .zoomGestures(
                    onApplyFactor = { factor ->
                        val current = MonitoringServiceController.zoomRatio().value
                        MonitoringServiceController.setZoomRatio(current * factor)
                    },
                    onReset = { MonitoringServiceController.setZoomRatio(1f) },
                ),
        ) {
            PreviewSurface(Modifier.fillMaxSize())
            RegionOverlay(
                regions = detectionRegions,
                rotationDegrees = rotationDegrees,
                modifier = Modifier.fillMaxSize(),
                show = showRegions,
            )
            ZoomBadge(
                zoomRatio = zoomRatio,
                modifier = Modifier.align(Alignment.TopStart),
            )
            IconButton(
                onClick = { showRegions = !showRegions },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = "Toggle detection regions",
                    tint = if (showRegions) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MonitorStatusBar(
            cameraName = cameraName,
            state = state,
            previewActive = previewActive,
            error = error,
            onStart = {
                val missing = viewModel.missingPermissions()
                if (missing.isEmpty()) viewModel.start()
                else permissionLauncher.launch(missing.toTypedArray())
            },
            onStop = viewModel::stop,
        )
    }
}

private val MonitorState.label: String
    get() = when (this) {
        MonitorState.Idle -> "Idle"
        MonitorState.Starting -> "Starting"
        MonitorState.Monitoring -> "Monitoring"
        MonitorState.Error -> "Error"
    }

@Composable
private fun MonitorStatusBar(
    cameraName: String,
    state: MonitorState,
    previewActive: Boolean,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val monitoring = state == MonitorState.Monitoring
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (previewActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$cameraName — ${state.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            // "Recording" indicator while the pipeline is live.
            if (monitoring) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.Red),
                )
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = if (monitoring) onStop else onStart) {
                Icon(
                    if (monitoring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (monitoring) "Stop" else "Start")
            }
        }
        if (state == MonitorState.Error && error != null) {
            Text(
                text = "Error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("monitorErrorBanner"),
            )
        }
    }
}