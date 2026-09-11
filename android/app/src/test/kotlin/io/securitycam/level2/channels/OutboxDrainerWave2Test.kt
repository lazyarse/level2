package io.securitycam.level2.channels

import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 2: unknown row kinds expire instead of retrying forever. */
class OutboxDrainerWave2Test {

    private class FakeQueue : io.securitycam.level2.storage.OutboxQueue {
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

    @Test
    fun unknownKindIsExpiredWithoutCallingSenders() = runBlocking {
        val queue = FakeQueue()
        queue.rows += OutboxEntity(id = 1, createdAt = 100L, kind = "warp-drive", channelId = "x")
        var notifyCalls = 0
        var backupCalls = 0
        val expired = mutableListOf<Long>()
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { notifyCalls++; false },
            sendBackup = { backupCalls++; false },
            onExpired = { expired.add(it.id) },
        )

        drainer.drainOnce()

        assertEquals(0, notifyCalls)
        assertEquals(0, backupCalls)
        assertEquals(listOf(1L), queue.deleted)
        assertEquals(listOf(1L), expired)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun knownKindsStillRouteToTheirSenders() = runBlocking {
        val queue = FakeQueue()
        queue.rows += OutboxEntity(id = 2, createdAt = 100L, kind = OutboxKind.BACKUP, mediaPath = "a.mp4")
        var backupCalls = 0
        val drainer = OutboxDrainer(
            queue = queue,
            nowMs = { 1_000L },
            sendNotify = { false },
            sendBackup = { backupCalls++; true },
        )

        drainer.drainOnce()

        assertEquals(1, backupCalls)
        assertEquals(listOf(2L), queue.deleted)
    }
}
