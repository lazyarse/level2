package io.securitycam.level1.event

import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.TriggerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Port of `test/trigger_batcher_test.dart`. */
class TriggerBatcherTest {

    private val t0 = Instant.parse("2026-01-01T12:00:00Z")

    private fun trigger(type: String, ts: Instant): TriggerEvent =
        TriggerEvent(timestamp = ts, triggerType = type, score = 0.9, detectorId = type)

    @Test
    fun mergesTriggersWithinTheWindowIntoOneBatch() = runBlocking {
        var snapshots = 0
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(200),
            captureSnapshot = {
                snapshots++
                Snapshot(ByteArray(0), "image/png", "s.png")
            },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        batcher.add(trigger("baby_cry", t0.plusMillis(50)))
        delay(300)
        assertEquals(1, batches.size)
        assertEquals(listOf("motion", "baby_cry"), batches.single().triggers.map { it.triggerType })
        assertNotNull(batches.single().snapshot)
        assertEquals(1, snapshots)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun flushesASeparateBatchAfterTheWindowElapses() = runBlocking {
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(180)
        batcher.add(trigger("baby_cry", t0.plusMillis(200)))
        delay(180)
        assertEquals(2, batches.size)
        assertEquals(listOf("motion"), batches[0].triggers.map { it.triggerType })
        assertEquals(listOf("baby_cry"), batches[1].triggers.map { it.triggerType })
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun captureFailureStillEmitsTheBatchWithANullSnapshot() = runBlocking {
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { throw IllegalStateException("no camera") },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(200)
        assertEquals(1, batches.size)
        assertNull(batches.single().snapshot)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun capturesVideoOnTheFirstTriggerAndNamesItOnTheBatch() = runBlocking {
        val received = mutableListOf<Instant>()
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
            captureVideo = { triggerAt ->
                received.add(triggerAt)
                "clip.mp4"
            },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        batcher.add(trigger("baby_cry", t0.plusMillis(40)))
        delay(200)
        assertEquals(1, batches.size)
        assertEquals("clip.mp4", batches.single().videoName)
        assertEquals(listOf(t0), received)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun videoCaptureFailureStillEmitsTheBatchWithANullVideoName() = runBlocking {
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
            captureVideo = { throw IllegalStateException("not monitoring") },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(200)
        assertEquals(1, batches.size)
        assertNull(batches.single().videoName)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun noCaptureVideoHookYieldsANullVideoName() = runBlocking {
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(200)
        assertEquals(1, batches.size)
        assertNull(batches.single().videoName)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun emitsTheBatchWhenVideoCaptureOutlivesTheWindow() = runBlocking {
        // Regression: flush() runs inside the timer coroutine; cancelling the
        // timer there used to kill flush at its next suspension point, so any
        // capture still in flight when the window elapsed silently dropped
        // the batch (only reproducible on device where captures take time).
        val videoDone = CompletableDeferred<String?>()
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
            captureVideo = { videoDone.await() },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(200)
        assertEquals(0, batches.size)
        videoDone.complete("clip.mp4")
        delay(100)
        assertEquals(1, batches.size)
        assertEquals("clip.mp4", batches.single().videoName)
        collector.cancel()
        batcher.dispose()
    }
}