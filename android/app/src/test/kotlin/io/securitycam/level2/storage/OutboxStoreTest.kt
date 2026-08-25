package io.securitycam.level2.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed outbox round-trip plus the late status flip on the events
 * table (Robolectric in-memory DB, schema v5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OutboxStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: OutboxStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = OutboxStore.from(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        createdAt: Long,
        kind: String = OutboxKind.NOTIFY,
        channelId: String? = "telegram",
        eventId: Long? = 42L,
    ): OutboxEntity = OutboxEntity(
        createdAt = createdAt,
        kind = kind,
        channelId = channelId,
        eventId = eventId,
        triggerType = "motion",
        eventTime = createdAt,
        text = "Motion detected in Hallway",
        snapshotName = null,
    )

    @Test
    fun peekBatchReturnsRowsOldestFirst() = runBlocking {
        store.enqueue(row(300))
        store.enqueue(row(100))
        store.enqueue(row(200))

        val batch = store.peekBatch()

        assertEquals(listOf(100L, 200L, 300L), batch.map { it.createdAt })
        assertEquals(OutboxKind.NOTIFY, batch[0].kind)
        assertEquals("telegram", batch[0].channelId)
        assertEquals(42L, batch[0].eventId)
    }

    @Test
    fun markAttemptedThenDeleteRoundTrip() = runBlocking {
        store.enqueue(row(100))
        val only = store.peekBatch().single()

        store.markAttempted(only.id, attempts = 3, lastAttemptAt = 999L)
        assertEquals(3, store.peekBatch().single().attempts)
        assertEquals(999L, store.peekBatch().single().lastAttemptAt)

        store.delete(only.id)
        assertTrue(store.peekBatch().isEmpty())
    }

    @Test
    fun dropExpiredRemovesOnlyPolicyViolatingRows() = runBlocking {
        val now = 10_000_000L
        store.enqueue(row(now - 1_000))                                    // fresh
        store.enqueue(
            row(now - 2_000).copy(attempts = OutboxPolicy.MAX_ATTEMPTS),
        )                                                                  // attempt-expired
        store.enqueue(
            row(now - OutboxPolicy.MAX_AGE.toMillis() * 4),
        )                                                                  // age-expired

        val dropped = store.dropExpired(now)

        assertEquals(2, dropped)
        val remaining = store.peekBatch()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().createdAt == now - 1_000)
    }

    @Test
    fun flipChannelStatusRewritesOnlyTheTargetChannel() = runBlocking {
        val log = RoomEventLog(db.eventDao())
        val eventId = log.record(
            io.securitycam.level2.event.RecordedEvent(
                timestamp = Instant.parse("2026-01-01T12:00:00Z"),
                cameraName = "Hallway",
                triggerType = "motion",
                score = 0.5,
                channelStatuses = mapOf("telegram" to "queued", "log" to "delivered"),
            ),
        )

        log.flipChannelStatus(eventId, "telegram", "delivered")

        val row = db.eventDao().byId(eventId)!!
        val statuses = decodeForTest(row.channelStatuses!!)
        assertEquals("delivered", statuses["telegram"])
        assertEquals("delivered", statuses["log"])
    }

    @Test
    fun flipChannelStatusIsANoOpForUnknownEvent() = runBlocking {
        val log = RoomEventLog(db.eventDao())
        // Must not throw; there is nothing to assert beyond absence of error.
        log.flipChannelStatus(123_456L, "telegram", "delivered")
        assertNull(db.eventDao().byId(123_456L))
    }

    /** Local re-implementation to avoid reaching into RoomEventLog privates. */
    private fun decodeForTest(raw: String): Map<String, String> {
        val obj = org.json.JSONObject(raw)
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.getString(key)
        }
        return out
    }
}
