package io.securitycam.level1.ui.events

import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.storage.RecordedEventRow
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for the history day-scoped state. */
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(
        id: Long,
        ts: Instant,
        snapshotName: String? = null,
    ) = RecordedEventRow(
        id = id,
        timestamp = ts,
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.8,
        snapshotName = snapshotName,
        videoName = null,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadsSelectedDayAndFiltersGallery() {
        val day1 = LocalDate.of(2026, 1, 5)
        val day2 = LocalDate.of(2026, 1, 6)
        val loaded = mutableMapOf<LocalDate, List<RecordedEventRow>>(
            day1 to listOf(
                row(1, Instant.parse("2026-01-05T22:30:00Z"), snapshotName = "a.png"),
                row(2, Instant.parse("2026-01-05T09:00:00Z")),
            ),
            day2 to listOf(row(3, Instant.parse("2026-01-06T10:00:00Z"))),
        )
        var requested: LocalDate? = null
        val vm = HistoryViewModel(
            dayLoader = { date ->
                requested = date
                loaded[date].orEmpty()
            },
            snapshotLoader = { Snapshot(byteArrayOf(1), "image/png", it) },
            initialDay = day1,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(day1, vm.day.value)
        assertEquals(requested, day1)
        assertEquals(2, vm.events.value!!.size)

        // Gallery keeps only snapshot rows.
        val gallery = vm.events.value!!.filter { it.snapshotName != null }
        assertEquals(listOf("a.png"), gallery.map { it.snapshotName })

        // Day navigation reloads for the new range.
        vm.nextDay()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(day2, vm.day.value)
        assertEquals(requested, day2)
        assertEquals(1, vm.events.value!!.size)

        vm.previousDay()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(day1, vm.day.value)

        vm.setDay(LocalDate.of(2026, 2, 1))
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(loaded[vm.day.value])
        assertEquals(0, vm.events.value!!.size)
    }
}
