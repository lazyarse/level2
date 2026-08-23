package io.securitycam.level1.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.event.triggerLabel
import io.securitycam.level1.storage.RecordedEventRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val GRID_COLUMNS = 3

/** How close to the end of the list the older-page fetch triggers, in items. */
private const val LOAD_MORE_THRESHOLD = 6

/**
 * Merged trigger-event browser: day-grouped paged list plus a per-day
 * snapshot gallery grid, with external-player clip playback.
 */
@Composable
fun EventsScreen(
    viewModel: EventsViewModel,
    modifier: Modifier = Modifier,
) {
    val sections by viewModel.sections.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val loadingOlder by viewModel.loadingOlder.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
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
                    onClick = { viewModel.setViewMode(EventsViewMode.LIST) },
                    modifier = Modifier.testTag("eventsViewList"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = "List view",
                        tint = if (viewMode == EventsViewMode.LIST) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = { viewModel.setViewMode(EventsViewMode.GRID) },
                    modifier = Modifier.testTag("eventsViewGrid"),
                ) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = "Grid view",
                        tint = if (viewMode == EventsViewMode.GRID) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = { viewModel.reload() },
                    modifier = Modifier.testTag("eventsReload"),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
            }
            val list = sections
            when {
                list == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                list.isEmpty() && !hasMore -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("No events yet") }

                else -> EventsList(
                    sections = list,
                    viewMode = viewMode,
                    loadingOlder = loadingOlder,
                    hasMore = hasMore,
                    snapshotLoader = viewModel::loadSnapshot,
                    onPlay = viewModel::playVideo,
                    showPlayButton = hasVideoOpener,
                    onLoadOlder = viewModel::loadOlder,
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventsList(
    sections: List<DaySection>,
    viewMode: EventsViewMode,
    loadingOlder: Boolean,
    hasMore: Boolean,
    snapshotLoader: suspend (String) -> Snapshot?,
    onPlay: (String) -> Unit,
    showPlayButton: Boolean,
    onLoadOlder: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Infinite scroll: near the bottom, ask the view-model for older pages.
    LaunchedEffect(listState, viewMode, sections) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last != null && last >= total - LOAD_MORE_THRESHOLD) {
                onLoadOlder()
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("eventsList")) {
        sections.forEach { section ->
            if (viewMode == EventsViewMode.GRID &&
                section.rows.none { it.snapshotName != null }
            ) {
                return@forEach
            }
            stickyHeader(key = "day_${section.date}") {
                DayHeader(section.date, section.rows.size)
            }
            if (viewMode == EventsViewMode.LIST) {
                itemsIndexed(section.rows, key = { _, row -> "event_${row.id}" }) { index, row ->
                    EventRow(
                        event = row,
                        snapshotLoader = snapshotLoader,
                        onPlay = onPlay,
                        showPlayButton = showPlayButton,
                    )
                    if (index < section.rows.lastIndex) HorizontalDivider()
                }
            } else {
                val tiles = section.rows.filter { it.snapshotName != null }
                tiles.chunked(GRID_COLUMNS).forEachIndexed { chunkIndex, chunk ->
                    item(key = "grid_${section.date}_$chunkIndex") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        ) {
                            chunk.forEach { row ->
                                GalleryTile(
                                    row = row,
                                    snapshotLoader = snapshotLoader,
                                    onPlay = onPlay,
                                    showPlayButton = showPlayButton,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(GRID_COLUMNS - chunk.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            Box(
                Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loadingOlder -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Loading older…")
                    }

                    !hasMore -> Text(
                        "No more events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, count: Int) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("dayHeader_$date"),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(
    event: RecordedEventRow,
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
    // Recognised-face events carry the person's name as detail; inline it.
    val faceName = event.detail?.takeIf {
        event.triggerTypes.contains(TriggerType.faceKnown) ||
            event.triggerType == TriggerType.faceKnown
    }
    val iconType = event.triggerTypes.firstOrNull() ?: event.triggerType
    val local = event.timestamp.atZone(ZoneId.systemDefault())
    val timeText = "%02d:%02d:%02d".format(local.hour, local.minute, local.second)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("eventRow_${event.id}"),
    ) {
        if (event.snapshotName == null) {
            Icon(
                eventIconFor(iconType),
                contentDescription = null,
                modifier = Modifier.testTag("eventIcon_${event.id}"),
            )
        } else {
            SnapshotThumb(
                name = event.snapshotName,
                fallbackIcon = eventIconFor(iconType),
                title = typeLabel,
                loader = snapshotLoader,
                tag = "eventThumb_${event.id}",
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$timeText · $typeLabel · score ${"%.2f".format(event.score)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildString {
                    if (faceName != null) {
                        append("Recognised: ")
                        append(faceName)
                        append(" — ")
                    }
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
                modifier = Modifier.testTag("eventPlay_${event.id}"),
            ) {
                Icon(Icons.Filled.PlayCircleOutline, contentDescription = "Play video")
            }
        }
    }
}

/** One gallery cell: square snapshot with an overlaid corner play button. */
@Composable
private fun GalleryTile(
    row: RecordedEventRow,
    snapshotLoader: suspend (String) -> Snapshot?,
    onPlay: (String) -> Unit,
    showPlayButton: Boolean,
    modifier: Modifier = Modifier,
) {
    val typeLabel = triggerLabel(row.triggerType)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            SnapshotThumb(
                name = row.snapshotName ?: return@Column,
                fallbackIcon = eventIconFor(row.triggerType),
                title = typeLabel,
                loader = snapshotLoader,
                tag = "galleryThumb_${row.id}",
                size = 110.dp,
            )
            if (row.videoName != null && showPlayButton) {
                IconButton(
                    onClick = { onPlay(row.videoName) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .testTag("galleryPlay_${row.id}"),
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
