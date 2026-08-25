package io.securitycam.level2.detection.face

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

/** Port of `test/face_detector_test.dart`. */
class FaceEngineTest {

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

    @Test
    fun noColorFrameNeverTriggers() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun faceAboveThresholdTriggersAfterPersistence() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.7, persistenceFrames = 2),
            engine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertEquals(true, r.triggered)
        d.dispose()
    }

    @Test
    fun faceBelowThresholdDoesNotTrigger() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.0, 0.0, 1.0, 1.0, 0.5))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.7, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun resultCarriesMaxFaceScore() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.0, 0.0, 1.0, 1.0, 0.6))
        engine.faces.add(FaceDetection(1.0, 1.0, 2.0, 2.0, 0.95))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = 1),
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
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.0, 0.0, 1.0, 1.0, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = 2),
            engine = engine,
        )
        d.init()
        d.analyzeFrameAsync(frame(base, c = color(140)))
        d.reset()
        val r = d.analyzeFrameAsync(frame(base.plusSeconds(1), c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    // Rect region covering the left half of the frame.
    private val halfRegion = DetectionRegion(
        id = "r1",
        shape = "rect",
        label = "left",
        points = listOf(0.0, 0.0, 0.5, 1.0),
    )

    @Test
    fun faceOutsideAllRegionsDoesNotTrigger() = runBlocking {
        // Normalized box x 0.567..0.933, outside the left-half region
        // [0,0.5]x[0,1].
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.567, 0.4, 0.933, 0.6, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = 1),
            engine = engine,
        )
        d.regions = listOf(halfRegion)
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun faceOverlappingARegionTriggers() = runBlocking {
        // Normalized box x 0.4..0.8 crosses the region's x=0.5 edge.
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.4, 0.4, 0.8, 0.6, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = 1),
            engine = engine,
        )
        d.regions = listOf(halfRegion)
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertTrue(r.triggered)
        d.dispose()
    }

    @Test
    fun emptyRegionsAllFacesPass() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.9, 0.1, 0.95, 0.2, 0.9))
        val d = FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = 1),
            engine = engine,
        )
        d.init()
        val r = d.analyzeFrameAsync(frame(base, c = color(140)))
        assertTrue(r.triggered)
        d.dispose()
    }
}