package io.securitycam.level1.detection

import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.TriggerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Port of `test/motion_detector_test.dart`. */
class MotionDetectorTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")

    private fun config(threshold: Double = 0.05, persistence: Int = 2): DetectorConfig =
        DetectorConfig(
            type = TriggerType.motion,
            threshold = threshold,
            persistenceFrames = persistence,
        )

    private fun frame(step: Int, bytes: ByteArray, width: Int, height: Int): AnalysisFrame =
        AnalysisFrame(
            timestamp = base.plusSeconds(step.toLong()),
            bitmap = GrayscaleBitmap(width, height, bytes),
        )

    @Test
    fun firstFramePrimesTheDetectorAndDoesNotTrigger() {
        val detector = MotionDetector(config())
        val result = detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16))
        assertFalse(result.triggered)
    }

    @Test
    fun identicalFramesNeverTrigger() {
        val detector = MotionDetector(config())
        for (step in 0 until 6) {
            val result = detector.analyzeFrame(frame(step, buildFrame(16, 16, 140), 16, 16))
            assertFalse("frame $step", result.triggered)
        }
    }

    @Test
    fun movingObjectTriggersAfterPersistenceFrames() {
        val detector = MotionDetector(config())
        assertFalse(detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16)).triggered)
        assertFalse(
            detector.analyzeFrame(
                frame(1, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16),
            ).triggered,
        )
        val result = detector.analyzeFrame(
            frame(2, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16),
        )
        assertTrue(result.triggered)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun belowThresholdJitterDoesNotTrigger() {
        val detector = MotionDetector(config(threshold = 0.5))
        for (step in 0 until 4) {
            val bytes = buildFrame(16, 16, 140)
            if (step % 2 == 0) bytes[0] = 141.toByte()
            val result = detector.analyzeFrame(frame(step, bytes, 16, 16))
            assertFalse("frame $step", result.triggered)
        }
    }

    @Test
    fun triggerResetsPersistenceSoDetectorMustReArm() {
        val detector = MotionDetector(config())
        val positions = intArrayOf(0, 2, 4, 2, 0, 2)
        for (step in positions.indices) {
            val p = positions[step]
            val bytes = buildFrameWithRect(16, 16, 140, p, p, 4, 4, 30)
            val result = detector.analyzeFrame(frame(step, bytes, 16, 16))
            val expected = step == 2 || step == 4
            assertTrue("frame $step expected=$expected", result.triggered == expected)
        }
    }

    @Test
    fun changeInsideARegionTriggersSameChangeOutsideDoesNot() {
        val detector = MotionDetector(config())
        detector.regions = listOf(
            DetectionRegion("r1", "rect", "doorway", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        // Outside: rect moves only in the bottom-right quadrant (outside region).
        detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16))
        detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 8, 8, 8, 8, 30), 16, 16))
        detector.analyzeFrame(frame(2, buildFrameWithRect(16, 16, 140, 10, 10, 8, 8, 30), 16, 16))
        assertFalse(
            detector.analyzeFrame(
                frame(3, buildFrameWithRect(16, 16, 140, 12, 12, 8, 8, 30), 16, 16),
            ).triggered,
        )

        // Inside: rect moves within the top-left quadrant (inside the region).
        detector.reset()
        detector.analyzeFrame(frame(4, buildFrame(16, 16, 140), 16, 16))
        detector.analyzeFrame(frame(5, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16))
        assertTrue(
            detector.analyzeFrame(
                frame(6, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16),
            ).triggered,
        )
    }

    @Test
    fun smallRegionDenominatorKeepsThresholdsMeaningful() {
        val detector = MotionDetector(config(threshold = 0.2, persistence = 2))
        detector.regions = listOf(
            DetectionRegion("r1", "rect", "q1", listOf(0.0, 0.0, 0.5, 0.5)),
        )
        detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16))
        detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 1, 1, 4, 4, 30), 16, 16))
        assertTrue(
            detector.analyzeFrame(
                frame(2, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16),
            ).triggered,
        )
    }

    @Test
    fun emptyRegionsEqualsLegacyWholeFrameBehavior() {
        val detector = MotionDetector(config())
        assertFalse(detector.analyzeFrame(frame(0, buildFrame(16, 16, 140), 16, 16)).triggered)
        detector.analyzeFrame(frame(1, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30), 16, 16))
        assertTrue(
            detector.analyzeFrame(
                frame(2, buildFrameWithRect(16, 16, 140, 4, 4, 4, 4, 30), 16, 16),
            ).triggered,
        )
    }
}