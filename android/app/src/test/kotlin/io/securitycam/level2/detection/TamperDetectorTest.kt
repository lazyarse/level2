package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Tests for the v1 tamper detector (`docs/plans/2026-08-19-tamper-detection-design.md`). */
class TamperDetectorTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")

    private fun config(persistence: Int = 2): DetectorConfig = DetectorConfig(
        type = TriggerType.tamper,
        threshold = 0.5,
        persistenceFrames = persistence,
    )

    private fun detector(warmUpFrames: Int = 3, persistence: Int = 2): TamperDetector =
        TamperDetector(config(persistence), warmUpFrames = warmUpFrames)

    private fun frame(step: Int, bytes: ByteArray, width: Int = 16, height: Int = 16): AnalysisFrame =
        AnalysisFrame(
            timestamp = base.plusSeconds(step.toLong()),
            bitmap = GrayscaleBitmap(width, height, bytes),
        )

    private fun uniform(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    /** Left [split] columns set to [value], rest at [base]. */
    private fun splitFrame(width: Int, height: Int, split: Int, value: Int, baseValue: Int): ByteArray {
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * width + x] =
                    (if (x < split) value else baseValue).toByte()
            }
        }
        return bytes
    }

    @Test
    fun warmUpFramesDoNotTriggerAndArmTheBaseline() {
        val d = detector(warmUpFrames = 3)
        // Dark frames during warm-up look like "covered" but must not fire.
        for (step in 0 until 3) {
            val r = d.analyzeFrame(frame(step, uniform(256, 10)))
            assertFalse("warm-up frame $step", r.triggered)
            assertEquals(null, r.detail)
        }
        // After warm-up the same darkness persists into "covered".
        val r = d.analyzeFrame(frame(3, uniform(256, 10)))
        assertFalse(r.triggered) // persistence counter starts only once armed
    }

    @Test
    fun sustainedBlackFramesTriggerCovered() {
        val d = detector(warmUpFrames = 3, persistence = 2)
        for (step in 0 until 3) d.analyzeFrame(frame(step, uniform(256, 128)))
        assertFalse(d.analyzeFrame(frame(3, uniform(256, 5))).triggered)
        val result = d.analyzeFrame(frame(4, uniform(256, 5)))
        assertTrue(result.triggered)
        assertEquals(TamperDetector.DETAIL_COVERED, result.detail)
        assertTrue(result.score > 0.9)
        assertEquals(TriggerType.tamper, result.triggerType)
    }

    @Test
    fun persistentSceneChangeWithStillFramesTriggersMoved() {
        val d = detector(warmUpFrames = 3, persistence = 2)
        for (step in 0 until 3) d.analyzeFrame(frame(step, uniform(256, 128)))
        // First frame after the swap carries high inter-frame motion.
        assertFalse(d.analyzeFrame(frame(3, uniform(256, 200))).triggered)
        assertFalse(d.analyzeFrame(frame(4, uniform(256, 200))).triggered)
        val result = d.analyzeFrame(frame(5, uniform(256, 200)))
        assertTrue(result.triggered)
        assertEquals(TamperDetector.DETAIL_MOVED, result.detail)
        assertTrue(result.score >= 0.5)
    }

    @Test
    fun movingSceneDoesNotTriggerMoved() {
        val d = detector(warmUpFrames = 3, persistence = 2)
        for (step in 0 until 3) d.analyzeFrame(frame(step, uniform(256, 128)))
        // Alternate two very different scenes every frame: big cell change vs
        // baseline but large inter-frame motion ⇒ physical movement, not tamper.
        for (step in 3 until 12) {
            val bytes = if (step % 2 == 0) {
                splitFrame(16, 16, 8, 200, 128)
            } else {
                splitFrame(16, 16, 8, 128, 200)
            }
            val r = d.analyzeFrame(frame(step, bytes))
            assertFalse("frame $step", r.triggered)
            assertEquals(null, r.detail)
        }
    }

    @Test
    fun resetRestartsWarmUpAndClearsState() {
        val d = detector(warmUpFrames = 3, persistence = 2)
        for (step in 0 until 3) d.analyzeFrame(frame(step, uniform(256, 128)))
        d.reset()
        // Warm-up restarts: dark frames during re-warm-up must not fire…
        for (step in 10 until 13) {
            val r = d.analyzeFrame(frame(step, uniform(256, 10)))
            assertFalse(r.triggered)
        }
        // …and the baseline is the new dark scene, so gray looks like "moved"
        // candidate only after persistence still frames.
        assertFalse(d.analyzeFrame(frame(13, uniform(256, 200))).triggered)
        assertFalse(d.analyzeFrame(frame(14, uniform(256, 200))).triggered)
        val result = d.analyzeFrame(frame(15, uniform(256, 200)))
        assertTrue(result.triggered)
        assertEquals(TamperDetector.DETAIL_MOVED, result.detail)
    }
}
