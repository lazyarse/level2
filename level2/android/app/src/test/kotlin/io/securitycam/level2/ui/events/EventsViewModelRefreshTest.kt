package io.securitycam.level2.ui.events

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.storage.RecordedEventRow
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression: the events screen paged via one-shot queries at init, so newly
 * recorded events stayed invisible until an app restart. The store's count
 * flow must trigger a live reload instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventsViewModelRefreshTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")

    private fun row(id: Long) = RecordedEventRow(
        id = id,
        timestamp = base,
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.5,
        snapshotName = null,
        videoName = null,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    private fun pump() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    @Test
    fun countIncreaseTriggersLiveReload() {
        val counts = MutableStateFlow(0L)
        val window = mutableListOf<RecordedEventRow>()
        var loadCalls = 0

        val vm = EventsViewModel(
            pageLoader = { _, _ ->
                loadCalls++
                window.toList()
            },
            floorLoader = { base },
            snapshotLoader = { null },
            videoOpener = null,
            countLoader = { counts },
            todayProvider = { java.time.LocalDate.parse("2026-01-02") },
        )
        assertEquals(0, vm.sections.value?.sumOf { it.rows.size })
        val initialCalls = loadCalls

        // A new event is recorded: the paged view gains the row and the count
        // flow bumps, which must re-run the initial load.
        window += row(1)
        counts.value = 1
        pump()

        assertTrue(loadCalls > initialCalls)
        assertEquals(1, vm.sections.value?.sumOf { it.rows.size })
    }

    @Test
    fun unchangedCountDoesNotReload() {
        val counts = MutableStateFlow(0L)
        var loadCalls = 0

        val vm = EventsViewModel(
            pageLoader = { _, _ ->
                loadCalls++
                emptyList()
            },
            floorLoader = { null },
            snapshotLoader = { null },
            videoOpener = null,
            countLoader = { counts },
            todayProvider = { java.time.LocalDate.parse("2026-01-02") },
        )
        pump()
        val afterInit = loadCalls

        counts.value = 0 // unchanged → distinctUntilChanged suppresses
        pump()

        assertEquals(afterInit, loadCalls)
    }
}
