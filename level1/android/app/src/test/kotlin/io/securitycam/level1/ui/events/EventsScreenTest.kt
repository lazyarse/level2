package io.securitycam.level1.ui.events

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.storage.RecordedEventRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("UTC")

    private class MemoryStore {
        val items = mutableMapOf<String, Snapshot>()
        suspend fun save(snapshot: Snapshot) {
            items[snapshot.name] = snapshot
        }

        suspend fun load(name: String): Snapshot? = items[name]
    }

    private fun row(
        id: Long,
        ts: Instant,
        snapshotName: String? = null,
        videoName: String? = null,
    ) = RecordedEventRow(
        id = id,
        timestamp = ts,
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.8,
        snapshotName = snapshotName,
        videoName = videoName,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    /** 1x1 PNG. */
    private fun tinyPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    /** In-memory paged log over UTC days. */
    private inner class FakeLog(rows: List<RecordedEventRow>) {
        val sorted = rows.sortedByDescending { it.timestamp }
        fun between(start: Instant, end: Instant) =
            sorted.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
        fun oldest(): Instant? = sorted.lastOrNull()?.timestamp
    }

    private fun setContent(
        rows: List<RecordedEventRow>,
        store: MemoryStore = MemoryStore(),
        videoOpener: ((String) -> String?)? = null,
        opened: MutableList<String>? = null,
    ): EventsViewModel {
        val log = FakeLog(rows)
        val vm = EventsViewModel(
            pageLoader = { start, end -> log.between(start, end) },
            floorLoader = { log.oldest() },
            snapshotLoader = { name -> runBlocking { store.load(name) } },
            videoOpener = videoOpener?.let { opener ->
                { name ->
                    opened?.add(name)
                    opener(name)
                }
            },
            todayProvider = { LocalDate.of(2026, 1, 5) },
            zone = zone,
        )
        compose.setContent { EventsScreen(viewModel = vm) }
        return vm
    }

    @Test
    fun rendersDayHeadersWithRowsBeneath() {
        setContent(
            listOf(
                row(1, Instant.parse("2026-01-05T22:30:00Z")),
                row(2, Instant.parse("2026-01-05T09:00:00Z")),
                row(3, Instant.parse("2026-01-03T10:00:00Z")),
            ),
        )

        compose.waitForIdle()
        compose.onNodeWithTag("dayHeader_2026-01-05").assertExists()
        compose.onNodeWithTag("dayHeader_2026-01-03").assertExists()
        compose.onNodeWithTag("eventRow_2").assertExists()
        compose.onNodeWithTag("eventRow_3").assertExists()
        // No header for the empty gap day.
        compose.onAllNodesWithTag("dayHeader_2026-01-04").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun rendersSnapshotThumbnailForAnEventWithASnapshot() {
        val store = MemoryStore()
        runBlocking { store.save(Snapshot(tinyPng(), "image/png", "snap-1.png")) }
        setContent(listOf(row(1, Instant.parse("2026-01-05T12:00:00Z"), snapshotName = "snap-1.png")), store)

        // Thumb decode now flows through the async cache.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("eventThumb_1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("eventThumb_1").assertExists()
        compose.onNodeWithTag("eventThumb_1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertExists()
    }

    @Test
    fun fallsBackToIconWhenTheSnapshotIsMissing() {
        setContent(listOf(row(1, Instant.parse("2026-01-05T12:00:00Z"), snapshotName = "missing.png")))

        compose.waitForIdle()
        compose.onAllNodesWithTag("eventThumb_1").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onNodeWithTag("eventThumb_1Fallback").assertExists()
    }

    @Test
    fun showsVideoButtonOnlyWhenVideoAttachedAndOpenerProvided() {
        val opened = mutableListOf<String>()
        setContent(
            listOf(
                row(1, Instant.parse("2026-01-05T12:00:00Z"), videoName = "clip.mp4"),
                row(2, Instant.parse("2026-01-05T11:00:00Z")),
            ),
            videoOpener = { null },
            opened = opened,
        )

        compose.waitForIdle()
        compose.onNodeWithTag("eventPlay_1").assertExists()
        compose.onAllNodesWithTag("eventPlay_2").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onNodeWithTag("eventPlay_1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("clip.mp4"), opened)
    }

    @Test
    fun noPlayButtonWhenOpenerNotProvided() {
        setContent(listOf(row(1, Instant.parse("2026-01-05T12:00:00Z"), videoName = "clip.mp4")))

        compose.waitForIdle()
        compose.onAllNodesWithTag("eventPlay_1").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun gridModeShowsPerDayTilesWithCornerPlayButtonsOnlyForSnapshots() {
        val opened = mutableListOf<String>()
        val store = MemoryStore()
        runBlocking { store.save(Snapshot(tinyPng(), "image/png", "snap-1.png")) }
        val vm = setContent(
            listOf(
                row(1, Instant.parse("2026-01-05T12:00:00Z"), snapshotName = "snap-1.png", videoName = "clip.mp4"),
                row(2, Instant.parse("2026-01-05T11:00:00Z")),
                row(3, Instant.parse("2026-01-04T10:00:00Z")),
            ),
            store = store,
            videoOpener = { null },
            opened = opened,
        )
        compose.waitForIdle()

        vm.setViewMode(EventsViewMode.GRID)
        compose.waitForIdle()

        compose.onNodeWithTag("galleryThumb_1").assertExists()
        compose.onNodeWithTag("galleryPlay_1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("clip.mp4"), opened)
        // Rows without snapshots render neither tiles nor play buttons.
        compose.onAllNodesWithTag("galleryThumb_2").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onAllNodesWithTag("galleryPlay_2").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        // Snapshot-less day sections are hidden entirely in grid mode.
        compose.onAllNodesWithTag("galleryThumb_3").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }
}
