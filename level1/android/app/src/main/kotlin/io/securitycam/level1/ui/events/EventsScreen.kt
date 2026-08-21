package io.securitycam.level1.ui.events

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.event.triggerLabel
import io.securitycam.level1.storage.RecordedEventRow

/** Trigger-event history with snapshot thumbnails and clip playback. */
@Composable
fun EventsScreen(
    viewModel: EventsViewModel,
    modifier: Modifier = Modifier,
) {
    val events by viewModel.events.collectAsState()
    val message by viewModel.message.collectAsState()
    val hasVideoOpener = viewModel.hasVideoOpener
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
            ) {
                Text(
                    "Trigger events",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.reload() },
                    modifier = Modifier.testTag("eventsReload"),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
            }
            val list = events
            when {
                list == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                list.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("No events yet") }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list) { index, event ->
                        EventRow(
                            event = event,
                            index = index,
                            snapshotLoader = viewModel::loadSnapshot,
                            onPlay = viewModel::playVideo,
                            showPlayButton = hasVideoOpener,
                        )
                        if (index < list.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
        )
    }
}

@Composable
private fun EventRow(
    event: RecordedEventRow,
    index: Int,
    snapshotLoader: suspend (String) -> Snapshot?,
    onPlay: (String) -> Unit,
    showPlayButton: Boolean,
) {
    val statuses = event.channelStatuses.entries.joinToString(", ") { (k, v) -> "$k=$v" }
    val typeLabel = if (event.triggerTypes.isEmpty()) {
        triggerLabel(event.triggerType)
    } else {
        event.triggerTypes.joinToString(" + ") { triggerLabel(it) }
    }
    val iconType = event.triggerTypes.firstOrNull() ?: event.triggerType

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (event.snapshotName == null) {
            Icon(
                iconFor(iconType),
                contentDescription = null,
                modifier = Modifier.testTag("eventIcon_$index"),
            )
        } else {
            SnapshotThumb(
                name = event.snapshotName,
                fallbackIcon = iconFor(iconType),
                title = typeLabel,
                loader = snapshotLoader,
                tag = "eventThumb_$index",
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$typeLabel · score ${"%.2f".format(event.score)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildString {
                    append(event.timestamp)
                    append(" — ")
                    append(event.cameraName)
                    if (statuses.isNotEmpty()) {
                        append(" — ")
                        append(statuses)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.videoName != null && showPlayButton) {
            IconButton(
                onClick = { onPlay(event.videoName) },
                modifier = Modifier.testTag("eventPlay_$index"),
            ) {
                Icon(Icons.Filled.PlayCircleOutline, contentDescription = "Play video")
            }
        }
    }
}

/**
 * 48dp thumbnail; falls back to [fallbackIcon] while loading or when the
 * snapshot is missing. Tap opens the zoomable full view.
 */
@Composable
private fun SnapshotThumb(
    name: String,
    fallbackIcon: ImageVector,
    title: String,
    loader: suspend (String) -> Snapshot?,
    tag: String,
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
            modifier = Modifier.size(48.dp).testTag("${tag}Fallback"),
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
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

@Composable
private fun ZoomableSnapshotDialog(
    snapshot: Snapshot?,
    title: String,
    onClose: () -> Unit,
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
                modifier = Modifier.testTag("eventClose"),
            ) { Text("Close") }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "motion" -> Icons.Filled.DirectionsRun
    "baby_cry" -> Icons.Filled.ChildCare
    "glass_break" -> Icons.Filled.BrokenImage
    "loud_noise" -> Icons.Filled.VolumeUp
    "tamper" -> Icons.Filled.VideocamOff
    else -> Icons.Filled.NotificationImportant
}
