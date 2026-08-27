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

class VehicleDetectorTest {

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
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun vehicleAboveThresholdTriggersAfterPersistence() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, threshold = 0.7, persistenceFrames = 2),
            engine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertTrue(r.triggered)
        assertEquals(TriggerType.vehicle, r.triggerType)
        d.dispose()
    }

    @Test
    fun vehicleBelowThresholdDoesNotTrigger() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.5))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, threshold = 0.7, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun noVehicleDetectionsDoesNotTrigger() = runBlocking {
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
            engine = MockVehicleEngine(),
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        assertEquals(0.0, r.score, 0.0)
        d.dispose()
    }

    @Test
    fun resultCarriesMaxVehicleScore() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.6))
        engine.vehicles.add(PersonBox(1.0, 1.0, 2.0, 2.0, 0.95))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertTrue(r.triggered)
        assertEquals(0.95, r.score, 1e-9)
        d.dispose()
    }

    @Test
    fun resetClearsPersistence() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 2),
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
    fun vehicleInsideExclusionZoneIsDropped() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(10.0, 10.0, 40.0, 40.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
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
    fun vehicleOutsideExclusionTriggers() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(60.0, 60.0, 90.0, 90.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
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
    fun vehicleOutsideInclusionsIsDropped() = runBlocking {
        val engine = MockVehicleEngine()
        engine.vehicles.add(PersonBox(60.0, 60.0, 90.0, 90.0, 0.9))
        val d = VehicleDetector(
            DetectorConfig(type = TriggerType.vehicle, persistenceFrames = 1),
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
