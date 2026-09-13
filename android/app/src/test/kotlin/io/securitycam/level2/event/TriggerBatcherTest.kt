package io.securitycam.level2.event

import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerEvent
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

    @Test
    fun slidingWindowKeepsAContinuousWaveInOneBatch() = runBlocking {
        // A wave whose triggers keep arriving within `window` of each other
        // must stay ONE batch, unlike the fixed-window split this replaces.
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(120),
            captureSnapshot = { null },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(100)
        batcher.add(trigger("motion", t0.plusMillis(200)))
        delay(100)
        batcher.add(trigger("motion", t0.plusMillis(300)))
        delay(100)
        batcher.add(trigger("motion", t0.plusMillis(400)))
        delay(100) // last trigger's window (120ms) hasn't elapsed → not flushed
        assertEquals(0, batches.size)
        delay(60) // window since the last trigger elapses → flushed
        assertEquals(1, batches.size)
        assertEquals(4, batches.single().triggers.size)
        assertEquals(t0, batches.single().timestamp)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun aQuietGapAfterTheWindowStartsANewBatch() = runBlocking {
        // Once no trigger has arrived for `window`, the wave is over: a later
        // trigger must open a separate batch.
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(200)
        batcher.add(trigger("baby_cry", t0.plusMillis(300)))
        delay(200)
        assertEquals(2, batches.size)
        assertEquals(listOf("motion"), batches[0].triggers.map { it.triggerType })
        assertEquals(listOf("baby_cry"), batches[1].triggers.map { it.triggerType })
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun onTriggerExtendedFiresForEachTriggerJoiningAnOpenBatch() = runBlocking {
        val extended = mutableListOf<Instant>()
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
            onTriggerExtended = { extended.add(Instant.now()) },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        batcher.add(trigger("motion", t0.plusMillis(30)))
        batcher.add(trigger("motion", t0.plusMillis(60)))
        delay(200)
        assertEquals(1, batches.size)
        // The first trigger opened the batch; the next two extended it.
        assertEquals(2, extended.size)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun onBatchCloseFiresWhenEachBatchFlushes() = runBlocking {
        var closes = 0
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(100),
            captureSnapshot = { null },
            captureVideo = { _ ->
                // Export resolves only AFTER close is signalled, mirroring the
                // recorder: it keeps recording until onBatchClose finalizes it.
                while (closes < 1) delay(5)
                "clip.mp4"
            },
            onBatchClose = { closes++ },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(trigger("motion", t0))
        delay(250)
        assertEquals(1, batches.size)
        assertEquals(1, closes)
        assertEquals("clip.mp4", batches.single().videoName)
        collector.cancel()
        batcher.dispose()
    }

    @Test
    fun maxBatchDurationForceFlushesEvenWhileTriggersKeepArriving() = runBlocking {
        // Perpetual motion (fan, swaying trees) must not produce ONE endless
        // event: the hard cap closes the batch and a new one opens.
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(150),
            captureSnapshot = { null },
            maxBatchDuration = Duration.ofMillis(300),
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        repeat(16) { i ->
            batcher.add(trigger("motion", t0.plusMillis(i * 60L)))
            delay(60)
        }
        delay(300)
        assert(batches.size >= 2) { "expected ≥2 capped batches, got ${batches.size}" }
        collector.cancel()
        batcher.dispose()
    }
}