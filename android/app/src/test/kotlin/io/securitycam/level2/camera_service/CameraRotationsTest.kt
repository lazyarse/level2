package io.securitycam.level2.camera_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the units contract: CameraX takes surface constants (0..3) while the
 * zone mapper works in degrees. Mixing them throws at bind time
 * (`Unsupported surface rotation`, seen on-device 2026-09-12), so the two
 * worlds convert exactly once per layer. Also pins the homogeneity invariant
 * that protects `bindToLifecycle()` from the 2026-08-23 regression.
 */
class CameraRotationsTest {

    @Test
    fun uniformSharesOneRotationAcrossUseCases() {
        for (r in 0..3) {
            val u = CameraRotations.uniform(r)
            assertEquals(r, u.analysis)
            assertEquals(r, u.preview)
            assertEquals(r, u.capture)
            assertEquals(r, u.video)
        }
    }

    @Test
    fun uniformRejectsDegrees() {
        try {
            CameraRotations.uniform(90)
            fail("degrees must not reach CameraX")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun orientationHintConvertsConstantToDegrees() {
        // Back sensor (90°): portrait capture plays with hint 90 (status
        // quo ante), landscape capture needs hint 0 — never 89°.
        assertEquals(90, CameraRotations.orientationHintFor(90, 0))
        assertEquals(0, CameraRotations.orientationHintFor(90, 1))
        assertEquals(270, CameraRotations.orientationHintFor(90, 2))
        assertEquals(180, CameraRotations.orientationHintFor(90, 3))
        // Front sensor (270°) stays in range.
        assertEquals(180, CameraRotations.orientationHintFor(270, 1))
    }

    @Test
    fun normalizeFoldsNegativesAndFullTurns() {
        assertEquals(0, CameraRotations.normalize(0))
        assertEquals(270, CameraRotations.normalize(-90))
        assertEquals(0, CameraRotations.normalize(360))
        assertEquals(90, CameraRotations.normalize(450))
    }
}
