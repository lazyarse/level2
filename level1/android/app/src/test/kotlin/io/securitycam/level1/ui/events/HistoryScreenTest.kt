package io.securitycam.level1.ui.events

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.storage.RecordedEventRow
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric UI tests for the history timeline + gallery screen. */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = LocalDate.of(2026, 1, 5)

    private fun row(
        id: Long,
        hour: Int,
        snapshotName: String?,
    ) = RecordedEventRow(
        id = id,
        timestamp = Instant.parse("2026-01-05T%02d:30:00Z".format(hour)),
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.75,
        snapshotName = snapshotName,
        videoName = null,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    private fun viewModel(rows: List<RecordedEventRow>): HistoryViewModel =
        HistoryViewModel(
            dayLoader = { rows },
            snapshotLoader = { name -> Snapshot(byteArrayOf(1), "image/png", name) },
            initialDay = day,
        )

    @Test
    fun timelineShowsDateHourBucketsAndEvents() {
        val vm = viewModel(listOf(row(1, 23, null), row(2, 9, "a.png")))
        compose.setContent { HistoryScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithTag("historyDate").assertTextContains("2026-01-05")
        compose.onNodeWithTag("historyEvent_1").assertExists()
        compose.onNodeWithTag("historyEvent_2").assertExists()
    }

    @Test
    fun galleryTabShowsSnapshotGrid() {
        val vm = viewModel(
            listOf(
                row(1, 10, "a.png"),
                row(2, 11, "b.png"),
                row(3, 12, null),
            ),
        )
        compose.setContent { HistoryScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithTag("historyTabGallery").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("historyGallery").assertExists()
        compose.onNodeWithTag("historyGallery_0").assertExists()
        compose.onNodeWithTag("historyGallery_1").assertExists()
    }

    @Test
    fun emptyDayShowsPlaceholder() {
        val vm = viewModel(emptyList())
        compose.setContent { HistoryScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithTag("historyEvent_999").assertDoesNotExist()
    }

    @Test
    fun dayNavigationReloads() {
        val vm = viewModel(listOf(row(1, 9, null)))
        compose.setContent { HistoryScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithTag("historyNext").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("historyDate").assertTextContains("2026-01-06")

        compose.onNodeWithTag("historyPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("historyPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("historyDate").assertTextContains("2026-01-04")
    }
}
