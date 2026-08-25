package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectionRegion
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.GrayscaleBitmap
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dog detector tests — mirrors PersonDetectorTest structure. */
class DogDetectorTest {

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun color(fill: Int): ColorBitmap {
        val bgr = ByteArray(3 * 3 * 3) { fill.toByte() }
        return ColorBitmap(3, 3, bgr)
    }

    private fun frame(ts: Instant, c: ColorBitmap? = null): AnalysisFrame = AnalysisFrame(
        timestamp = ts,
        bitmap = GrayscaleBitmap(3, 3, ByteArray(9)),
        color = c,
    )

    private fun bigColor(): ColorBitmap = ColorBitmap(100, 100, ByteArray(3 * 100 * 100))

    @Test
    fun noColorFrameNeverTriggers() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 1),
            visualEngine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun dogAboveThresholdTriggersAfterPersistence() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.7, persistenceFrames = 2),
            visualEngine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertTrue(r.triggered)
        assertEquals(TriggerType.dog, r.triggerType)
        d.dispose()
    }

    @Test
    fun dogBelowThresholdDoesNotTrigger() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.5))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.7, persistenceFrames = 1),
            visualEngine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun noDogDetectionsDoesNotTrigger() = runBlocking {
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 1),
            visualEngine = MockDogEngine(),
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        assertEquals(0.0, r.score, 0.0)
        d.dispose()
    }

    @Test
    fun resultCarriesMaxDogScore() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.6))
        engine.dogs.add(PersonBox(1.0, 1.0, 2.0, 2.0, 0.95))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 1),
            visualEngine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertTrue(r.triggered)
        assertEquals(0.95, r.score, 1e-9)
        d.dispose()
    }

    @Test
    fun resetClearsPersistence() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 2),
            visualEngine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        d.reset()
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun dogInsideExclusionZoneIsDropped() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(10.0, 10.0, 40.0, 40.0, 0.9))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 1),
            visualEngine = engine,
        )
        d.init()
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        val r = d.analyzeFrameAsync(frame(base, c = bigColor()))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun dogOutsideExclusionTriggers() = runBlocking {
        val engine = MockDogEngine()
        engine.dogs.add(PersonBox(60.0, 60.0, 90.0, 90.0, 0.9))
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, persistenceFrames = 1),
            visualEngine = engine,
        )
        d.init()
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        val r = d.analyzeFrameAsync(frame(base, c = bigColor()))
        assertTrue(r.triggered)
        d.dispose()
    }
}
