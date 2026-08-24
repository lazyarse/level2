package io.securitycam.level1.channels

import io.securitycam.level1.storage.OutboxEntity
import io.securitycam.level1.storage.OutboxKind
import io.securitycam.level1.storage.OutboxPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the outbox drain rules: FIFO order, per-row attempt
 * counting, expiry callbacks, and the has-more signal for the scheduler.
 */
class OutboxDrainerTest {

    /** In-memory [OutboxQueue] preserving FIFO order. */
    private class FakeQueue : io.securitycam.level1.storage.OutboxQueue {
        val rows = mutableListOf<OutboxEntity>()
        val deleted = mutableListOf<Long>()

        override suspend fun peekBatch(limit: Int): List<OutboxEntity> =
            rows.sortedWith(compareBy({ it.createdAt }, { it.id })).take(limit)

        override suspend fun markAttempted(id: Long, attempts: Int, lastAttemptAt: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(attempts = attempts, lastAttemptAt = lastAttemptAt)
        }

        override suspend fun delete(id: Long) {
            deleted.add(id)
            rows.removeAll { it.id == id }
        }
    }

    private fun row(
        id: Long,
        createdAt: Long,
        attempts: Int = 0,
        kind: String = OutboxKind.NOTIFY,
    ): OutboxEntity = OutboxEntity(
        id = id,
        createdAt = createdAt,
        kind = kind,
        channelId = "telegram",
        eventId = id,
        text = "t$id",
        attempts = attempts,
    )

    @Test
    fun deliversInFifoOrderAndRemovesDeliveredRows() = runBlocking {
        val queue = FakeQueue()
        queue.rows += listOf(row(1, 100), row(2, 200), row(3, 300))
        val deliveredOrder = mutableListOf<Long>()
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { deliveredOrder.add(requireNotNull(it.eventId)); true },
        )

        val remaining = drainer.drainOnce()

        assertFalse(remaining)
        assertEquals(listOf(1L, 2L, 3L), deliveredOrder)
        assertEquals(listOf(1L, 2L, 3L), queue.deleted)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun failedSendIncrementsAttemptsAndKeepsRow() = runBlocking {
        val queue = FakeQueue()
        queue.rows += row(7, 100)
        var calls = 0
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { calls++; false },
        )

        val remaining = drainer.drainOnce()

        assertTrue(remaining)
        assertEquals(1, calls)
        assertEquals(1, queue.rows.single().attempts)
        assertEquals(1_000L, queue.rows.single().lastAttemptAt)
        assertTrue(queue.deleted.isEmpty())
    }

    @Test
    fun expiredRowsAreDroppedWithCallbackWithoutSending() = runBlocking {
        val queue = FakeQueue()
        // attempts at MAX → expired regardless of age.
        queue.rows += row(1, 100, attempts = OutboxPolicy.MAX_ATTEMPTS)
        // Age beyond MAX_AGE → expired. Its createdAt sorts before row 1.
        queue.rows += row(2, 1_000L - OutboxPolicy.MAX_AGE.toMillis() * 4)
        val sent = mutableListOf<OutboxEntity>()
        val expired = mutableListOf<Long>()
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { sent.add(it); true },
            onExpired = { expired.add(requireNotNull(it.eventId)) },
        )

        drainer.drainOnce()

        assertTrue(sent.isEmpty())
        assertEquals(listOf(2L, 1L), expired)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun unknownKindCountsAsFailureNotCrash() = runBlocking {
        val queue = FakeQueue()
        queue.rows += row(9, 100, kind = "mystery")
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { true },
        )

        val remaining = drainer.drainOnce()

        assertTrue(remaining)
        assertEquals(1, queue.rows.single().attempts)
    }

    @Test
    fun backupKindIsRoutedToTheBackupSender() = runBlocking {
        val queue = FakeQueue()
        queue.rows += row(5, 100, kind = OutboxKind.BACKUP)
        var notifyCalls = 0
        var backupCalls = 0
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { notifyCalls++; false },
            sendBackup = { backupCalls++; true },
        )

        val remaining = drainer.drainOnce()

        assertFalse(remaining)
        assertEquals(0, notifyCalls)
        assertEquals(1, backupCalls)
    }
}
