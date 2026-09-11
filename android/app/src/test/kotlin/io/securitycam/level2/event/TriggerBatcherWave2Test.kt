package io.securitycam.level2.event

import io.securitycam.level2.core.TriggerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Wave 2: dispose cancels in-flight captures; batch timestamps stay intact. */
class TriggerBatcherWave2Test {

    private val t0 = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun disposeCancelsAnInFlightSnapshotSoTheBatchNeverLeaks() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(50),
            captureSnapshot = {
                gate.await()
                null
            },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(TriggerEvent(t0, "motion", 0.9, "motion"))
        delay(120) // window elapsed; flush is now parked in capture await
        batcher.dispose()
        gate.complete(Unit)
        delay(120)
        assertEquals(0, batches.size)
        collector.cancel()
    }

    @Test
    fun normalBatchKeepsTheFirstTriggerTimestamp() = runBlocking {
        val batcher = TriggerBatcher(
            scope = this,
            window = Duration.ofMillis(80),
            captureSnapshot = { null },
        )
        val batches = mutableListOf<TriggerBatch>()
        val collector = launch { batcher.batches.collect { batches.add(it) } }
        batcher.add(TriggerEvent(t0, "motion", 0.9, "motion"))
        batcher.add(TriggerEvent(t0.plusMillis(10), "motion", 0.9, "motion"))
        withTimeout(2000) {
            while (batches.isEmpty()) delay(20)
        }
        assertEquals(t0, batches.single().timestamp)
        assertEquals(0L, batcher.droppedBatches)
        collector.cancel()
        batcher.dispose()
    }
}
