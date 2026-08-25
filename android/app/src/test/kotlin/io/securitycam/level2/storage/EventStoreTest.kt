package io.securitycam.level2.storage

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.event.DeletedMedia
import io.securitycam.level2.event.RecordedEvent
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Port of `test/event_log_test.dart`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventStoreTest {

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun log(): RoomEventLog {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RoomEventLog(db.eventDao())
    }

    private fun event(
        ts: Instant,
        triggerType: String = "motion",
        snapshotName: String? = null,
        videoName: String? = null,
        statuses: Map<String, String> = emptyMap(),
        types: List<String> = emptyList(),
    ): RecordedEvent = RecordedEvent(
        timestamp = ts,
        cameraName = "Hallway",
        triggerType = triggerType,
        score = 0.9,
        snapshotName = snapshotName,
        videoName = videoName,
        channelStatuses = statuses,
        triggerTypes = types,
    )

    @Test
    fun recordInsertsAQueryableRow() = runBlocking {
        val store = log()
        store.record(event(base, statuses = mapOf("log" to "delivered"), types = listOf("motion", "baby_cry")))

        val rows = store.recent()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Hallway", row.cameraName)
        assertEquals("motion", row.triggerType)
        assertEquals(0.9, row.score, 0.0)
        assertEquals(mapOf("log" to "delivered"), row.channelStatuses)
        assertEquals(listOf("motion", "baby_cry"), row.triggerTypes)
    }

    @Test
    fun recentReturnsNewestFirstWithLimit() = runBlocking {
        val store = log()
        store.record(event(base))
        store.record(event(base.plusSeconds(10)))
        store.record(event(base.plusSeconds(20)))

        val rows = store.recent(limit = 2)
        assertEquals(2, rows.size)
        assertTrue(rows[0].timestamp.isAfter(rows[1].timestamp))
    }

    @Test
    fun deleteOlderThanReturnsAffectedMediaNames() = runBlocking {
        val store = log()
        store.record(event(base, snapshotName = "old.png", videoName = "old.mp4"))
        store.record(event(base.plusSeconds(100), snapshotName = "new.png"))

        val deleted: DeletedMedia = store.deleteEvents(olderThan = base.plusSeconds(50))

        assertEquals(listOf("old.png"), deleted.snapshotNames)
        assertEquals(listOf("old.mp4"), deleted.videoNames)
        assertEquals(1, store.recent().size)
        assertEquals("new.png", store.recent().single().snapshotName)
    }

    @Test
    fun deleteAllPurgesEverything() = runBlocking {
        val store = log()
        store.record(event(base, snapshotName = "a.png"))
        store.record(event(base.plusSeconds(1), snapshotName = "b.png"))

        val deleted = store.deleteEvents(olderThan = null)

        assertEquals(setOf("a.png", "b.png"), deleted.snapshotNames.toSet())
        assertTrue(store.recent().isEmpty())
    }

    // ---- History day-scoped queries (`between`) ----

    @Test
    fun betweenReturnsRowsInsideBoundsNewestFirst() = runBlocking {
        val store = log()
        store.record(event(base))
        store.record(event(base.plusSeconds(10)))
        store.record(event(base.minusSeconds(10)))
        store.record(event(base.plusSeconds(100))) // outside

        val start = base.minusSeconds(30)
        val end = base.plusSeconds(30)
        val rows = store.between(start, end)

        assertEquals(3, rows.size)
        assertEquals(base.plusSeconds(10), rows[0].timestamp)
        assertEquals(base, rows[1].timestamp)
        assertEquals(base.minusSeconds(10), rows[2].timestamp)
    }

    @Test
    fun betweenBoundsAreStartInclusiveEndExclusive() = runBlocking {
        val store = log()
        store.record(event(base)) // == start
        store.record(event(base.plusSeconds(60))) // == end

        val rows = store.between(base, base.plusSeconds(60))

        assertEquals(1, rows.size)
        assertEquals(base, rows.single().timestamp)
    }

    @Test
    fun betweenWithSnapshotsKeepsOnlySnapshotRows() = runBlocking {
        val store = log()
        store.record(event(base, snapshotName = "a.png"))
        store.record(event(base.plusSeconds(5)))
        store.record(event(base.plusSeconds(10), snapshotName = "c.png"))

        val rows = store.between(
            base.minusSeconds(1),
            base.plusSeconds(60),
            withSnapshots = true,
        )

        assertEquals(listOf("c.png", "a.png"), rows.map { it.snapshotName })
    }

    @Test
    fun betweenRespectsLimit() = runBlocking {
        val store = log()
        for (s in 0 until 5) store.record(event(base.plusSeconds(s.toLong())))

        val rows = store.between(base.minusSeconds(1), base.plusSeconds(60), limit = 3)

        assertEquals(3, rows.size)
    }
}