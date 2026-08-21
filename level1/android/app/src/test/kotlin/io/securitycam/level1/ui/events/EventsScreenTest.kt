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

    private class MemoryStore {
        val items = mutableMapOf<String, Snapshot>()
        suspend fun save(snapshot: Snapshot) {
            items[snapshot.name] = snapshot
        }

        suspend fun load(name: String): Snapshot? = items[name]
    }

    private fun row(
        snapshotName: String? = null,
        videoName: String? = null,
    ) = RecordedEventRow(
        id = 1,
        timestamp = Instant.parse("2026-01-01T12:00:00Z"),
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

    private fun setContent(
        rows: List<RecordedEventRow>,
        store: MemoryStore = MemoryStore(),
        videoOpener: ((String) -> String?)? = null,
        opened: MutableList<String>? = null,
    ): EventsViewModel {
        val vm = EventsViewModel(
            loader = { rows },
            snapshotLoader = { name -> runBlocking { store.load(name) } },
            videoOpener = videoOpener?.let { opener ->
                { name ->
                    opened?.add(name)
                    opener(name)
                }
            },
        )
        compose.setContent { EventsScreen(viewModel = vm) }
        return vm
    }

    @Test
    fun rendersSnapshotThumbnailForAnEventWithASnapshot() {
        val store = MemoryStore()
        runBlocking { store.save(Snapshot(tinyPng(), "image/png", "snap-1.png")) }
        setContent(listOf(row(snapshotName = "snap-1.png")), store)

        compose.waitForIdle()
        compose.onNodeWithText("Motion · score 0.80").assertExists()
        compose.onNodeWithTag("eventThumb_0").assertExists()
        compose.onNodeWithTag("eventThumb_0").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertExists()
    }

    @Test
    fun fallsBackToIconWhenTheSnapshotIsMissing() {
        setContent(listOf(row(snapshotName = "missing.png")))

        compose.waitForIdle()
        compose.onAllNodesWithTag("eventThumb_0").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onNodeWithTag("eventThumb_0Fallback").assertExists()
    }

    @Test
    fun showsVideoButtonOnlyWhenVideoAttachedAndOpenerProvided() {
        val opened = mutableListOf<String>()
        setContent(
            listOf(row(videoName = "clip.mp4"), row()),
            videoOpener = { null },
            opened = opened,
        )

        compose.waitForIdle()
        compose.onNodeWithTag("eventPlay_0").assertExists()
        compose.onAllNodesWithTag("eventPlay_1").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onNodeWithTag("eventPlay_0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("clip.mp4"), opened)
    }

    @Test
    fun noPlayButtonWhenOpenerNotProvided() {
        setContent(listOf(row(videoName = "clip.mp4")))

        compose.waitForIdle()
        compose.onAllNodesWithTag("eventPlay_0").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }
}
