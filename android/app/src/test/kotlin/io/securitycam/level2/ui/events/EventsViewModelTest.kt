package io.securitycam.level2.ui.events

import io.securitycam.level2.storage.RecordedEventRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for day-chunked paging of the merged events view-model. */
class EventsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 1, 5)

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

    private fun ts(day: LocalDate, hour: Int, minute: Int = 0): Instant =
        day.atTime(hour, minute).toInstant(ZoneOffset.UTC)

    private fun row(id: Long, ts: Instant) = RecordedEventRow(
        id = id,
        timestamp = ts,
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.8,
        snapshotName = null,
        videoName = null,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    private class FakeLog(rows: List<RecordedEventRow>) {
        val sorted = rows.sortedByDescending { it.timestamp }
        var queries = 0
        fun between(start: Instant, end: Instant): List<RecordedEventRow> {
            queries++
            return sorted.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
        }

        fun oldest(): Instant? = sorted.lastOrNull()?.timestamp
    }

    private fun vm(log: FakeLog): EventsViewModel = EventsViewModel(
        pageLoader = { start, end -> log.between(start, end) },
        floorLoader = { log.oldest() },
        snapshotLoader = { null },
        videoOpener = null,
        todayProvider = { today },
        zone = zone,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun initialLoadGroupsDaysNewestFirstSkippingGaps() = runTest(dispatcher.scheduler) {
        val log = FakeLog(
            listOf(
                row(1, ts(today, 22, 30)),
                row(2, ts(today, 9)),
                row(3, ts(today.minusDays(1), 8)),
                row(4, ts(today.minusDays(3), 7)),
            ),
        )
        val model = vm(log)
        advanceUntilIdle()

        val dates = model.sections.value.orEmpty().map { it.date }
        assertEquals(listOf(today, today.minusDays(1), today.minusDays(3)), dates)
        assertEquals(listOf(1L, 2L), model.sections.value!![0].rows.map { it.id })
        assertFalse(model.hasMore.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadOlderSkipsEmptyGapsUntilItFindsEvents() = runTest(dispatcher.scheduler) {
        val oldDay = today.minusDays(20)
        val log = FakeLog(listOf(row(9, ts(oldDay, 5))))
        val model = vm(log)
        advanceUntilIdle()
        assertEquals(emptyList<DaySection>(), model.sections.value)

        model.loadOlder()
        advanceUntilIdle()

        assertEquals(listOf(oldDay), model.sections.value.orEmpty().map { it.date })
        // The found day contains the log floor, so paging is done.
        assertFalse(model.hasMore.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun scrollingPastTheFloorDisablesFurtherPaging() = runTest(dispatcher.scheduler) {
        val oldestDay = today.minusDays(30)
        val log = FakeLog(
            listOf(row(1, ts(today, 12)), row(2, ts(oldestDay, 6))),
        )
        val model = vm(log)
        advanceUntilIdle()

        while (model.hasMore.value) {
            model.loadOlder()
            advanceUntilIdle()
        }

        assertEquals(
            setOf(today, oldestDay),
            model.sections.value.orEmpty().map { it.date }.toSet(),
        )
        assertFalse(model.hasMore.value)

        val sectionsBefore = model.sections.value
        model.loadOlder()
        advanceUntilIdle()
        assertEquals(sectionsBefore, model.sections.value)
        assertFalse(model.loadingOlder.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun emptyLogShowsNoSectionsAndNoMorePages() = runTest(dispatcher.scheduler) {
        val log = FakeLog(emptyList())
        val model = vm(log)
        advanceUntilIdle()

        assertEquals(emptyList<DaySection>(), model.sections.value)
        assertFalse(model.hasMore.value)
        assertNull(model.message.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun playVideoSurfacesOpenerErrorsAsMessages() = runTest(dispatcher.scheduler) {
        val log = FakeLog(emptyList())
        val model = EventsViewModel(
            pageLoader = { start, end -> log.between(start, end) },
            floorLoader = { log.oldest() },
            snapshotLoader = { null },
            videoOpener = { "boom" },
            todayProvider = { today },
            zone = zone,
        )
        advanceUntilIdle()

        model.playVideo("clip.mp4")
        advanceUntilIdle()

        assertEquals("Could not play video: boom", model.message.value)
        model.consumeMessage()
        assertNull(model.message.value)
    }
}
