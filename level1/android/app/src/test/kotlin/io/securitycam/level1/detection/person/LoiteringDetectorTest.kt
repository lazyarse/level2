package io.securitycam.level1.detection.person

import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.GrayscaleBitmap
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwell/grace/re-arm semantics of [LoiteringDetector] over a mock engine.
 */
class LoiteringDetectorTest {

    private val start: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private class OnePersonEngine : PersonEngine {
        var present = true
        var score = 0.9
        override suspend fun init() {}
        override suspend fun dispose() {}
        override suspend fun detectPersons(frame: ColorBitmap): List<PersonBox> =
            if (present) listOf(PersonBox(0.0, 0.0, 50.0, 50.0, score)) else emptyList()
    }

    private fun config(dwellSeconds: Int = 10) = DetectorConfig(
        type = TriggerType.loitering,
        threshold = 0.5,
        persistenceFrames = 2,
        dwellSeconds = dwellSeconds,
    )

    private fun frame(atMs: Long) = AnalysisFrame(
        timestamp = start.plusMillis(atMs),
        bitmap = GrayscaleBitmap(100, 100, ByteArray(100 * 100)),
        color = ColorBitmap(100, 100, ByteArray(100 * 100 * 3)),
    )

    @Test
    fun firesOnceAfterCumulativeDwell() = runBlocking {
        val engine = OnePersonEngine()
        val d = LoiteringDetector(config(), engine)

        // 5 x 2s presence frames (first frame contributes no delta) = 8s < 10s.
        var firedAt = -1L
        for (t in 0..4) {
            val r = d.analyzeFrameAsync(frame(t * 2000L))
            if (r.triggered) firedAt = t.toLong()
        }
        assertEquals(-1L, firedAt)

        // t=10s → cumulative 10s → fires with detail carrying the seconds.
        val fire = d.analyzeFrameAsync(frame(10_000))
        assertTrue(fire.triggered)
        assertEquals("loitered 10s", fire.detail)
        assertEquals(TriggerType.loitering, fire.triggerType)

        // Still present afterwards → stays quiet until the person leaves.
        assertFalse(d.analyzeFrameAsync(frame(12_000)).triggered)
    }

    @Test
    fun briefOcclusionKeepsTheDwellClock() = runBlocking {
        val engine = OnePersonEngine()
        val d = LoiteringDetector(config(), engine)

        for (t in 0..4) d.analyzeFrameAsync(frame(t * 2000L)) // 8s accumulated
        engine.present = false
        d.analyzeFrameAsync(frame(10_000))                     // absent 2s (< grace)
        engine.present = true
        // Resume within the grace window: clock paused, not reset.
        assertFalse(d.analyzeFrameAsync(frame(11_000)).triggered)
        // 2s more presence → crosses the 10s line without restarting.
        val fire = d.analyzeFrameAsync(frame(13_000))
        assertTrue(fire.triggered)
    }

    @Test
    fun realAbsenceResetsAndRearmsForANewAlert() = runBlocking {
        val engine = OnePersonEngine()
        val d = LoiteringDetector(config(), engine)

        for (t in 0..4) d.analyzeFrameAsync(frame(t * 2000L))
        val first = d.analyzeFrameAsync(frame(10_000))
        assertTrue(first.triggered)

        engine.present = false
        d.analyzeFrameAsync(frame(20_000)) // > grace: episode ends, clock resets
        engine.present = true

        // Needs a full new dwell window; nothing left over from episode one.
        var refired = false
        for (t in 6..11) {
            if (d.analyzeFrameAsync(frame((t + 10) * 2000L)).triggered) refired = true
        }
        assertTrue(refired)
    }

    @Test
    fun weakBoxesDoNotAccumulateDwell() = runBlocking {
        val engine = OnePersonEngine().apply { score = 0.3 } // below threshold 0.5
        val d = LoiteringDetector(config(), engine)

        var fired = false
        for (t in 0..10) {
            if (d.analyzeFrameAsync(frame(t * 2000L)).triggered) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun resetClearsAllState() = runBlocking {
        val engine = OnePersonEngine()
        val d = LoiteringDetector(config(), engine)
        for (t in 0..4) d.analyzeFrameAsync(frame(t * 2000L))
        d.reset()

        // Fresh instance semantics: full dwell needed again from t=0 baseline.
        var firedEarly = false
        for (t in 100..103) {
            if (d.analyzeFrameAsync(frame(t * 2000L)).triggered) firedEarly = true
        }
        assertFalse(firedEarly)
    }
}
