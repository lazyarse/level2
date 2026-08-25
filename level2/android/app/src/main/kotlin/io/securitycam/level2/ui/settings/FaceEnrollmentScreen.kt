package io.securitycam.level2.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.securitycam.level2.ui.monitor.PreviewSurface

/**
 * Full-screen live capture page for face enrollment: a large camera viewfinder
 * (backed by whichever camera session is running — the temporary preview-only
 * session or active monitoring) with status text and a Cancel action. Hosted by
 * [io.securitycam.level2.SecurityCamApp] while enrollment is in flight; it is
 * dismissed automatically when the enrollment finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceEnrollmentScreen(
    label: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onFlipCamera: () -> Unit = {},
    canFlipCamera: Boolean = true,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Enrol face") },
                actions = {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("cancelEnrollmentButton"),
                    ) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                PreviewSurface(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(16.dp))
                        .testTag("enrollmentPreview"),
                )
                // Front/back flip for the capture session only; disabled when
                // another session (monitoring) owns the camera.
                IconButton(
                    onClick = onFlipCamera,
                    enabled = canFlipCamera,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .testTag("flipEnrollmentCameraButton"),
                ) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = "Flip camera",
                        tint = if (canFlipCamera) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Enrolling $label…",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Position the face in the frame and hold still.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
