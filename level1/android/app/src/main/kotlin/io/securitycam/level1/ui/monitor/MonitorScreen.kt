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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Face
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.monitor.MonitorState
import io.securitycam.level1.monitor.MonitorViewModel
import io.securitycam.level1.ui.events.eventIconFor

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val healthStalled by viewModel.healthStalled.collectAsStateWithLifecycle()
    val previewActive by viewModel.previewActive.collectAsStateWithLifecycle()
    val cameraName by viewModel.cameraName.collectAsStateWithLifecycle()
    val detectionRegions by viewModel.detectionRegions.collectAsStateWithLifecycle()
    val exclusionRegions by viewModel.exclusionRegions.collectAsStateWithLifecycle()
    val zoomRatio by MonitoringServiceController.zoomRatio().collectAsStateWithLifecycle()
    val activeTriggers by viewModel.activeTriggers.collectAsStateWithLifecycle()

    // Whether Start or Preview initiated the permission request, so the grant
    // callback resumes the action the user actually tapped.
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (viewModel.hasCorePermissions()) {
            (pendingPermissionAction ?: { viewModel.start() }).invoke()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    // `display` throws on contexts without an associated display (e.g. JVM
    // tests); rotation 0 is the safe fallback.
    val displayRotation = runCatching { LocalContext.current.display?.rotation }
        .getOrNull() ?: Surface.ROTATION_0
    val rotationDegrees = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    var showRegions by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .zoomGestures(
                    onApplyFactor = { factor ->
                        MonitoringServiceController.setZoomRatio(zoomRatio * factor)
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
                exclusionRegions = exclusionRegions,
            )
            ZoomBadge(
                zoomRatio = zoomRatio,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                text = cameraName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 36.dp, top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(onClick = { showRegions = !showRegions }) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = "Toggle detection regions",
                        tint = if (showRegions) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.cycleCamera() }) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = "Switch camera",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        MonitorStatusBar(
            cameraName = cameraName,
            state = state,
            previewActive = previewActive,
            error = error,
            healthStalled = healthStalled,
            activeTriggers = activeTriggers,
            onStart = {
                val missing = viewModel.missingPermissions()
                if (missing.isEmpty()) viewModel.start()
                else {
                    pendingPermissionAction = { viewModel.start() }
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onStop = viewModel::stop,
            onStartPreview = {
                val missing = viewModel.missingPermissions()
                if (missing.isEmpty()) viewModel.startPreview()
                else {
                    pendingPermissionAction = { viewModel.startPreview() }
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onStopPreview = viewModel::stopPreview,
        )
    }
}

private val MonitorState.label: String
    get() = when (this) {
        MonitorState.Idle -> "Idle"
        MonitorState.Starting -> "Starting"
        MonitorState.Monitoring -> "Monitoring"
        MonitorState.Previewing -> "Previewing"
        MonitorState.Error -> "Error"
    }

@Composable
private fun MonitorStatusBar(
    cameraName: String,
    state: MonitorState,
    previewActive: Boolean,
    error: String?,
    healthStalled: Boolean,
    activeTriggers: Set<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    val monitoring = state == MonitorState.Monitoring
    val previewing = state == MonitorState.Previewing
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
                text = state.label,
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
            // Triggered detector icons; the face family shares one glyph whose
            // color reflects recognition outcome (green = known, red = other).
            if (activeTriggers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    activeTriggers.forEach { type ->
                        val faceFamily = type == TriggerType.face ||
                            type == TriggerType.faceKnown ||
                            type == TriggerType.faceUnknown
                        Icon(
                            imageVector = if (faceFamily) {
                                Icons.Filled.Face
                            } else {
                                eventIconFor(type)
                            },
                            contentDescription = type,
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("triggerIcon_$type"),
                            tint = when {
                                type == TriggerType.faceKnown ->
                                    Color(0xFF4CAF50)
                                faceFamily -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (monitoring) {
                Button(onClick = onStop, modifier = Modifier.testTag("stopMonitorButton")) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = state != MonitorState.Starting,
                    modifier = Modifier.testTag("startMonitorButton"),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (state == MonitorState.Error) "Retry" else "Start")
                }
                Spacer(Modifier.width(4.dp))
                when {
                    state == MonitorState.Idle -> Button(
                        onClick = onStartPreview,
                        modifier = Modifier.testTag("previewCameraButton"),
                    ) {
                        Text("Preview")
                    }

                    previewing -> IconButton(
                        onClick = onStopPreview,
                        modifier = Modifier.testTag("stopPreviewButton"),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop preview",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Cancel a slow service start; Error relies on Retry above.
                    state == MonitorState.Starting -> IconButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("cancelStartButton"),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Cancel startup",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Unit
                }
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
        if (healthStalled) {
            Text(
                text = "Camera feed stalled",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Red.copy(alpha = 0.75f))
                    .padding(4.dp)
                    .testTag("healthBanner"),
            )
        }
    }
}
