package io.securitycam.level2.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.event.triggerLabel
import io.securitycam.level2.storage.RecordedEventRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val GRID_COLUMNS = 3

/** How close to the end of the list the older-page fetch triggers, in items. */
private const val LOAD_MORE_THRESHOLD = 6

/** Fractional position (0–1) within the day for a 24-hour timeline. */
private fun fractionOfDay(timestamp: java.time.Instant): Float {
    val local = timestamp.atZone(ZoneId.systemDefault())
    return (local.hour * 3600 + local.minute * 60 + local.second) / 86400f
}

private fun confidenceLabel(score: Double): String = when {
    score < 0.5 -> "Low"
    score < 0.75 -> "Med"
    else -> "High"
}

private fun triggerColor(type: String): Color = when (type) {
    TriggerType.faceKnown -> Color(0xFF4CAF50)
    TriggerType.face, TriggerType.faceUnknown -> Color(0xFFE53935)
    TriggerType.dog, TriggerType.cat, TriggerType.bird, TriggerType.livestock ->
        Color(0xFFFFA726)
    TriggerType.loudNoise, TriggerType.babyCry, TriggerType.glassBreak ->
        Color(0xFF42A5F5)
    TriggerType.vehicle -> Color(0xFF7E57C2)
    else -> Color(0xFF2196F3)
}

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
                    "Events",
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
                    onClick = { viewModel.setViewMode(EventsViewMode.TIMELINE) },
                    modifier = Modifier.testTag("eventsViewTimeline"),
                ) {
                    Icon(
                        Icons.Filled.Timeline,
                        contentDescription = "Timeline view",
                        tint = if (viewMode == EventsViewMode.TIMELINE) {
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
        if (viewMode == EventsViewMode.TIMELINE) {
            item(key = "timelineHint") {
                Text(
                    "Pinch to zoom, drag to pan the timeline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
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
            } else if (viewMode == EventsViewMode.TIMELINE) {
                item(key = "timeline_${section.date}") {
                    TimelineDay(
                        section = section,
                        snapshotLoader = snapshotLoader,
                        onPlay = onPlay,
                        showPlayButton = showPlayButton,
                    )
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
        Text("$label ($count)", style = MaterialTheme.typography.titleSmall)
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
                "$timeText · $typeLabel",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Confidence: ${confidenceLabel(event.score)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun TimelineDay(
    section: DaySection,
    snapshotLoader: suspend (String) -> Snapshot?,
    onPlay: (String) -> Unit,
    showPlayButton: Boolean,
) {
    val rows = section.rows.sortedBy { it.timestamp }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selectedRow = rows.find { it.id == selectedId }

    // Zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        // Timeline bar
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            val barWidth = maxWidth

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 12f)
                            val barWidthPx = barWidth.value
                            val maxPan = barWidthPx * (1f - 1f / scale)
                            panOffset = (panOffset + pan.x).coerceIn(-maxPan, maxPan)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            panOffset = 0f
                        })
                    },
            ) {
                val barWidthPx = barWidth.value
                val visibleHours = 24f / scale
                val panHours = panOffset / barWidthPx * 24f
                val startHour = ((12f - visibleHours / 2f) - panHours)
                    .coerceIn(0f, 24f - visibleHours)
                val endHour = startHour + visibleHours

                fun hourToX(hour: Float) = barWidth * ((hour - startHour) / visibleHours)

                // Horizontal axis line
                val lineY = 16.dp
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .offset(y = lineY),
                ) {
                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                    )
                }

                // Dynamic tick spacing based on zoom level
                val tickInterval = when {
                    visibleHours <= 2f -> 0.5f
                    visibleHours <= 4f -> 1f
                    visibleHours <= 8f -> 2f
                    else -> 6f
                }
                val showMinutes = visibleHours <= 4f

                // Hour ticks and labels
                var tickHour = (startHour / tickInterval).toInt() * tickInterval
                while (tickHour <= endHour) {
                    if (tickHour >= 0f && tickHour <= 24f) {
                        val xDp = hourToX(tickHour)
                        Canvas(
                            modifier = Modifier
                                .width(1.dp)
                                .height(10.dp)
                                .offset(x = xDp, y = lineY + 3.dp),
                        ) {
                            drawLine(
                                color = Color.Gray,
                                start = Offset(size.width / 2, 0f),
                                end = Offset(size.width / 2, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        val label = if (showMinutes) {
                            "%02d:%02d".format(tickHour.toInt(), ((tickHour % 1) * 60).toInt())
                        } else {
                            "%02d:00".format(tickHour.toInt().coerceAtMost(23))
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .width(36.dp)
                                .offset(x = xDp - 18.dp, y = lineY + 14.dp),
                        )
                    }
                    tickHour += tickInterval
                }

                // Event dots
                rows.forEach { row ->
                    val eventHour = fractionOfDay(row.timestamp) * 24f
                    if (eventHour < startHour || eventHour > endHour) return@forEach
                    val xDp = hourToX(eventHour)
                    val dotColor = triggerColor(
                        row.triggerTypes.firstOrNull() ?: row.triggerType,
                    )
                    val isSelected = selectedId == row.id
                    val dotSize = if (isSelected) 14.dp else 10.dp
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .offset(x = xDp - dotSize / 2, y = lineY - dotSize / 2 + 1.dp)
                            .background(dotColor, shape = CircleShape)
                            .clickable {
                                selectedId = if (selectedId == row.id) null else row.id
                            },
                    )
                }
            }
        }

        // Expanded detail card
        if (selectedRow != null) {
            Spacer(Modifier.height(4.dp))
            TimelineDetailCard(
                event = selectedRow,
                snapshotLoader = snapshotLoader,
                onPlay = onPlay,
                showPlayButton = showPlayButton,
            )
        }
    }
}

@Composable
private fun TimelineDetailCard(
    event: RecordedEventRow,
    snapshotLoader: suspend (String) -> Snapshot?,
    onPlay: (String) -> Unit,
    showPlayButton: Boolean,
) {
    val typeLabel = if (event.triggerTypes.isEmpty()) {
        triggerLabel(event.triggerType)
    } else {
        event.triggerTypes.joinToString(" + ") { triggerLabel(it) }
    }
    val iconType = event.triggerTypes.firstOrNull() ?: event.triggerType
    val faceName = event.detail?.takeIf {
        event.triggerTypes.contains(TriggerType.faceKnown) ||
            event.triggerType == TriggerType.faceKnown
    }
    val local = event.timestamp.atZone(ZoneId.systemDefault())
    val timeText = "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
    val statuses = event.channelStatuses.entries.joinToString(", ") { (k, v) -> "$k=$v" }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .testTag("timelineDetail_${event.id}"),
    ) {
        if (event.snapshotName == null) {
            Icon(
                eventIconFor(iconType),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            SnapshotThumb(
                name = event.snapshotName,
                fallbackIcon = eventIconFor(iconType),
                title = typeLabel,
                loader = snapshotLoader,
                tag = "timelineThumb_${event.id}",
                size = 36.dp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$timeText · $typeLabel",
                style = MaterialTheme.typography.bodyMedium,
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
            IconButton(onClick = { onPlay(event.videoName) }) {
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
    val local = row.timestamp.atZone(ZoneId.systemDefault())
    val timeText = "%02d:%02d".format(local.hour, local.minute)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                eventIconFor(row.triggerType),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                timeText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}
