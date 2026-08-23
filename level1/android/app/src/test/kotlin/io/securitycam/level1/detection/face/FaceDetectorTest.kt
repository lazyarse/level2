package io.securitycam.level1.detection.face

import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.GrayscaleBitmap
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exclusion/inclusion region semantics of the face trigger. */
class FaceDetectorTest {

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** 100x100 color frame so pixel-coordinate engine boxes map to 0..1 cleanly. */
    private fun frame(ts: Instant): AnalysisFrame = AnalysisFrame(
        timestamp = ts,
        bitmap = GrayscaleBitmap(3, 3, ByteArray(9)),
        color = ColorBitmap(100, 100, ByteArray(3 * 100 * 100)),
    )

    private fun detector(engine: MockFaceEngine, persistence: Int = 1): FaceDetector =
        FaceDetector(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = persistence),
            engine = engine,
        )

    @Test
    fun faceInsideExclusionZoneIsDropped() = runBlocking {
        val engine = MockFaceEngine()
        // Pixels (10..40)^2 -> 0.1..0.4 normalized, inside the 0..0.5 exclusion.
        engine.faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9))
        val d = detector(engine)
        d.init()
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun faceOutsideExclusionTriggers() = runBlocking {
        val engine = MockFaceEngine()
        // Pixels (60..90)^2 -> 0.6..0.9 normalized, clear of the 0..0.5 exclusion.
        engine.faces.add(FaceDetection(0.6, 0.6, 0.9, 0.9, 0.9))
        val d = detector(engine)
        d.init()
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        val r = d.analyzeFrameAsync(frame(base))
        assertTrue(r.triggered)
        d.dispose()
    }

    @Test
    fun faceOutsideInclusionsIsDropped() = runBlocking {
        val engine = MockFaceEngine()
        engine.faces.add(FaceDetection(0.6, 0.6, 0.9, 0.9, 0.9))
        val d = detector(engine)
        d.init()
        d.regions = listOf(
            DetectionRegion("r1", "rect", "focus", listOf(0.0, 0.0, 0.4, 0.4)),
        )
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }

    @Test
    fun exclusionWinsOverInclusionOverlap() = runBlocking {
        val engine = MockFaceEngine()
        // Pixels (20..70)^2 -> 0.2..0.7 normalized: overlaps both zones.
        engine.faces.add(FaceDetection(0.2, 0.2, 0.7, 0.7, 0.9))
        val d = detector(engine)
        d.init()
        d.regions = listOf(
            DetectionRegion("r1", "rect", "focus", listOf(0.0, 0.0, 0.8, 0.8)),
        )
        d.exclusionRegions = listOf(
            DetectionRegion("e1", "rect", "private", listOf(0.4, 0.4, 1.0, 1.0)),
        )
        val r = d.analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
        d.dispose()
    }
}
