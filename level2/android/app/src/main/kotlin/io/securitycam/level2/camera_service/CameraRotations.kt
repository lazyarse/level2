package io.securitycam.level2.camera_service

/**
 * Single source of truth for the per-use-case target rotations handed to
 * CameraX.
 *
 * Regression guard (2026-08-23): an experiment fed divergent target rotations
 * (capture/video=90 while preview/analysis=0) into one UseCaseGroup; on some
 * HALs `bindToLifecycle()` then failed session configuration for BOTH retry
 * attempts — monitoring ran with the mic chip only and a black preview.
 *
 * Invariant under test: every use case in the group shares ONE rotation
 * value, drawn from the live display rotation, each a valid quarter-turn.
 */
data class UseCaseRotations(
    val analysis: Int,
    val preview: Int,
    val capture: Int,
    val video: Int,
)

object CameraRotations {

    val VALID: Set<Int> = setOf(0, 90, 180, 270)

    /**
     * [displayRotation] is the value CameraX expects for screen-relative
     * outputs (preview/capture/video); analysis frames get their pixels
     * rotated manually before publish ([FrameRotation]), but declaring the
     * same target keeps the group's session configuration homogeneous.
     */
    fun resolve(displayRotation: Int): UseCaseRotations {
        // Snap defensively to the nearest quarter-turn: an out-of-domain value
        // (e.g. 45 from a misread display) must never reach CameraX.
        val r = normalize(Math.round(displayRotation / 90f) * 90)
        return UseCaseRotations(analysis = r, preview = r, capture = r, video = r)
    }

    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
