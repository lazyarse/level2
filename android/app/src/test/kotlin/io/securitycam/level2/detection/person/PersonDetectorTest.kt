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

/** Port of `test/person_detector_test.dart`. */
class PersonDetectorTest {

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

    /** 100x100 color frame so pixel-coordinate engine boxes map to 0..1 cleanly. */
    private fun bigColor(): ColorBitmap = ColorBitmap(100, 100, ByteArray(3 * 100 * 100))

    @Test
    fun noColorFrameNeverTriggers() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun personAboveThresholdTriggersAfterPersistence() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, threshold = 0.7, persistenceFrames = 2),
            engine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertEquals(true, r.triggered)
        assertEquals(TriggerType.person, r.triggerType)
        d.dispose()
    }

    @Test
    fun personBelowThresholdDoesNotTrigger() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.5))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, threshold = 0.7, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun noPersonDetectionsDoesNotTrigger() = runBlocking {
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = MockPersonEngine(),
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        assertEquals(0.0, r.score, 0.0)
        d.dispose()
    }

    @Test
    fun resultCarriesMaxPersonScore() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.6))
        engine.persons.add(PersonBox(1.0, 1.0, 2.0, 2.0, 0.95))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertEquals(true, r.triggered)
        assertEquals(0.95, r.score, 1e-9)
        d.dispose()
    }

    @Test
    fun resetClearsPersistence() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 2),
            engine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        d.reset()
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun personInsideExclusionZoneIsDropped() = runBlocking {
        val engine = MockPersonEngine()
        // Engine boxes are pixel coords on a 100x100 frame: (10..40)^2 -> 0.1..0.4 normalized.
        engine.persons.add(PersonBox(10.0, 10.0, 40.0, 40.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = engine,
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
    fun personOutsideExclusionTriggers() = runBlocking {
        val engine = MockPersonEngine()
        // Pixels (60..90)^2 -> 0.6..0.9 normalized, clear of the 0..0.5 exclusion.
        engine.persons.add(PersonBox(60.0, 60.0, 90.0, 90.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        val r = d.analyzeFrameAsync(frame(base, c = bigColor()))
        assertTrue(r.triggered)
        d.dispose()
    }

    @Test
    fun personOutsideInclusionsIsDropped() = runBlocking {
        val engine = MockPersonEngine()
        engine.persons.add(PersonBox(60.0, 60.0, 90.0, 90.0, 0.9))
        val d = PersonDetector(
            DetectorConfig(type = TriggerType.person, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        d.regions = listOf(
            DetectionRegion("r1", "rect", "focus", listOf(0.0, 0.0, 0.4, 0.4)),
        )
        val r = d.analyzeFrameAsync(frame(base, c = bigColor()))
        assertFalse(r.triggered)
        d.dispose()
    }
}