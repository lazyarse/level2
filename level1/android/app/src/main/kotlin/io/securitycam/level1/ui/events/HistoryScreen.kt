package io.securitycam.level1.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.event.triggerLabel
import io.securitycam.level1.storage.RecordedEventRow
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History tab: day-scoped timeline (hour buckets + activity bars) and a
 * snapshot gallery grid, sharing the events log (port of
 * `docs/plans/2026-08-19-history-timeline-gallery-design.md`).
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)) {
    val day by viewModel.day.collectAsState()
    val events by viewModel.events.collectAsState()
    val message by viewModel.message.collectAsState()
    val hasVideoOpener = viewModel.hasVideoOpener
    var subTab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                IconButton(
                    onClick = viewModel::previousDay,
                    modifier = Modifier.testTag("historyPrev"),
                ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous day") }
                Text(
                    text = day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag("historyDate"),
                )
                IconButton(
                    onClick = viewModel::nextDay,
                    modifier = Modifier.testTag("historyNext"),
                ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next day") }
            }
            TabRow(
                selectedTabIndex = subTab,
                modifier = Modifier.testTag("historyTabs"),
            ) {
                Tab(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    text = { Text("Timeline") },
                    modifier = Modifier.testTag("historyTabTimeline"),
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text("Gallery") },
                    modifier = Modifier.testTag("historyTabGallery"),
                )
            }
            val list = events
            when {
                list == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                subTab == 0 -> TimelineList(
                    events = list,
                    snapshotLoader = viewModel::loadSnapshot,
                    hasVideoOpener = hasVideoOpener,
                    onPlay = viewModel::playVideo,
                )
                else -> GalleryGrid(
                    events = list,
                    snapshotLoader = viewModel::loadSnapshot,
                    hasVideoOpener = hasVideoOpener,
                    onPlay = viewModel::playVideo,
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Day timeline: per-hour buckets with activity bars, then that hour's events. */
@Composable
private fun TimelineList(
    events: List<RecordedEventRow>,
    snapshotLoader: suspend (String) -> Snapshot?,
    hasVideoOpener: Boolean,
    onPlay: (String) -> Unit,
) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events this day")
        }
        return
    }
    // Rows arrive newest first; group into ascending-hour buckets for display.
    val zone = ZoneId.systemDefault()
    val withLocal = events.map { it to it.timestamp.atZone(zone) }.asReversed()
    val buckets = withLocal.groupBy { it.second.hour }.toSortedMap()
    val maxCount = buckets.values.maxOf { it.size }

    LazyColumn(Modifier.fillMaxSize()) {
        buckets.forEach { (hour, entries) ->
            item(key = "hour_$hour") {
                HourHeader(hour = hour, count = entries.size, maxCount = maxCount)
            }
            itemsIndexed(entries) { index, entry ->
                val (row, local) = entry
                TimelineRow(
                    row = row,
                    timeText = "%02d:%02d".format(local.hour, local.minute),
                    snapshotLoader = snapshotLoader,
                    hasVideoOpener = hasVideoOpener,
                    onPlay = onPlay,
                )
                if (index < entries.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HourHeader(hour: Int, count: Int, maxCount: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d:00".format(hour),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (maxCount == 0) 0f else count.toFloat() / maxCount },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun TimelineRow(
    row: RecordedEventRow,
    timeText: String,
    snapshotLoader: suspend (String) -> Snapshot?,
    hasVideoOpener: Boolean,
    onPlay: (String) -> Unit,
) {
    val typeLabel = if (row.triggerTypes.isEmpty()) {
        triggerLabel(row.triggerType)
    } else {
        row.triggerTypes.joinToString(" + ") { triggerLabel(it) }
    }
    val iconType = row.triggerTypes.firstOrNull() ?: row.triggerType

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("historyEvent_${row.id}"),
    ) {
        if (row.snapshotName == null) {
            Icon(eventIconFor(iconType), contentDescription = null)
        } else {
            SnapshotThumb(
                name = row.snapshotName,
                fallbackIcon = eventIconFor(iconType),
                title = typeLabel,
                loader = snapshotLoader,
                tag = "historyThumb_${row.id}",
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$timeText · $typeLabel · score ${"%.2f".format(row.score)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.cameraName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.videoName != null && hasVideoOpener) {
            IconButton(
                onClick = { onPlay(row.videoName) },
                modifier = Modifier.testTag("historyPlay_${row.id}"),
            ) {
                Icon(Icons.Filled.PlayCircleOutline, contentDescription = "Play video")
            }
        }
    }
}

/** Snapshot gallery for the selected day: 3-column grid of thumbnails. */
@Composable
private fun GalleryGrid(
    events: List<RecordedEventRow>,
    snapshotLoader: suspend (String) -> Snapshot?,
    hasVideoOpener: Boolean,
    onPlay: (String) -> Unit,
) {
    val withSnapshots = events.filter { it.snapshotName != null }
    if (withSnapshots.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No snapshots this day")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().padding(4.dp).testTag("historyGallery"),
    ) {
        itemsIndexed(withSnapshots) { index, row ->
            val typeLabel = triggerLabel(row.triggerType)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    SnapshotThumb(
                        name = row.snapshotName ?: return@Box,
                        fallbackIcon = eventIconFor(row.triggerType),
                        title = typeLabel,
                        loader = snapshotLoader,
                        tag = "historyGallery_$index",
                        size = 110.dp,
                    )
                    if (row.videoName != null && hasVideoOpener) {
                        IconButton(
                            onClick = { onPlay(row.videoName) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(32.dp)
                                .testTag("galleryPlay_$index"),
                        ) {
                            Icon(
                                Icons.Filled.PlayCircleOutline,
                                contentDescription = "Play video",
                                tint = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
