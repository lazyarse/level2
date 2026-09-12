package io.securitycam.level2.camera_service

/**
 * Single source of truth for the per-use-case target rotations handed to
 * CameraX.
 *
 * Two unit systems meet here — keep them straight:
 * - CameraX `setTargetRotation` takes **surface constants**
 *   (`ROTATION_0`=0 … `ROTATION_270`=3); anything else (e.g. degrees like 90)
 *   throws `IllegalArgumentException: Unsupported surface rotation`.
 *   Verified on-device 2026-09-12.
 * - [ZoneDisplayMapper] and the UI overlay work in **degrees**.
 * Convert between them with `* 90` exactly once per layer.
 *
 * Regression guard (2026-08-23): an experiment fed divergent target rotations
 * into one UseCaseGroup; on some HALs `bindToLifecycle()` then failed session
 * configuration for BOTH retry attempts — monitoring ran with the mic chip
 * only and a black preview. [uniform] keeps the group homogeneous.
 */
data class UseCaseRotations(
    val analysis: Int,
    val preview: Int,
    val capture: Int,
    val video: Int,
)

object CameraRotations {

    val VALID: Set<Int> = setOf(0, 90, 180, 270)

    /** Homogeneous group rotation: every use case shares one surface constant. */
    fun uniform(surfaceRotation: Int): UseCaseRotations {
        require(surfaceRotation in 0..3) { "surface rotation must be 0..3, was $surfaceRotation" }
        return UseCaseRotations(
            analysis = surfaceRotation,
            preview = surfaceRotation,
            capture = surfaceRotation,
            video = surfaceRotation,
        )
    }

    /**
     * Muxer orientation hint (degrees) for recorded clips: sensor orientation
     * minus the bound video rotation. Both inputs are sensor/constant-domain —
     * callers must convert the surface constant (×90) first; feeding the raw
     * constant yields garbage like 89° (seen on-device 2026-09-12).
     */
    fun orientationHintFor(sensorOrientationDegrees: Int, videoRotationConst: Int): Int =
        (sensorOrientationDegrees - videoRotationConst * 90 + 360) % 360

    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
