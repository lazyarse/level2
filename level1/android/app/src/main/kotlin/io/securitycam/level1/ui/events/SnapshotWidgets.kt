package io.securitycam.level1.ui.events

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.securitycam.level1.core.Snapshot

/** Icon for a trigger type, shared by the events/history surfaces. */
internal fun eventIconFor(type: String): ImageVector = when (type) {
    "motion" -> Icons.Filled.DirectionsRun
    "baby_cry" -> Icons.Filled.ChildCare
    "glass_break" -> Icons.Filled.BrokenImage
    "loud_noise" -> Icons.Filled.VolumeUp
    "tamper" -> Icons.Filled.VideocamOff
    else -> Icons.Filled.NotificationImportant
}

/**
 * Snapshot thumbnail with a fallback icon while loading / when missing; tap
 * opens the zoomable full view. Shared by Events rows and the History gallery.
 */
@Composable
internal fun SnapshotThumb(
    name: String,
    fallbackIcon: ImageVector,
    title: String,
    loader: suspend (String) -> Snapshot?,
    tag: String,
    size: Dp = 48.dp,
) {
    val snapshot by produceState<Snapshot?>(initialValue = null, key1 = name) {
        value = loader(name)
    }
    val bitmap = snapshot?.bytes?.let { bytes ->
        remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    var showFull by remember { mutableStateOf(false) }

    if (bitmap == null) {
        Icon(
            fallbackIcon,
            contentDescription = null,
            modifier = Modifier.size(size).testTag("${tag}Fallback"),
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .clickable { showFull = true }
                .testTag(tag),
        )
    }
    if (showFull) {
        ZoomableSnapshotDialog(
            snapshot = snapshot,
            title = title,
            onClose = { showFull = false },
        )
    }
}

/** Zoomable (pinch 1x–8x + pan) full-size snapshot dialog. */
@Composable
internal fun ZoomableSnapshotDialog(
    snapshot: Snapshot?,
    title: String,
    onClose: () -> Unit,
    closeTag: String = "eventClose",
) {
    Dialog(onDismissRequest = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, modifier = Modifier.padding(8.dp))
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }
            val bitmap = snapshot?.bytes?.let { bytes ->
                remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            },
                    )
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag(closeTag),
            ) { Text("Close") }
        }
    }
}
