package io.securitycam.level1.camera_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariant that protects `bindToLifecycle()` from the 2026-08-23
 * regression: all use cases in a group must declare the SAME target rotation,
 * and every value must be a valid quarter-turn.
 */
class CameraRotationsTest {

    @Test
    fun allUseCasesShareTheDisplayRotation() {
        for (display in listOf(0, 90, 180, 270)) {
            val r = CameraRotations.resolve(display)
            assertEquals(display, r.analysis)
            assertEquals(r.analysis, r.preview)
            assertEquals(r.analysis, r.capture)
            assertEquals(r.analysis, r.video)
        }
    }

    @Test
    fun rotationsAreValidQuarterTurns() {
        for (display in listOf(-90, 0, 45, 90, 180, 270, 360, 810)) {
            val r = CameraRotations.resolve(display)
            assertTrue(
                "analysis=$display not normalized",
                r.analysis in CameraRotations.VALID,
            )
            // Snapping keeps the group uniform even for off-domain inputs
            // (45 rounds up to 90).
            assertEquals(r.analysis, r.preview)
            assertEquals(r.analysis, r.capture)
            assertEquals(r.analysis, r.video)
        }
    }

    @Test
    fun normalizeFoldsNegativesAndFullTurns() {
        assertEquals(0, CameraRotations.normalize(0))
        assertEquals(270, CameraRotations.normalize(-90))
        assertEquals(0, CameraRotations.normalize(360))
        assertEquals(90, CameraRotations.normalize(450))
    }
}
