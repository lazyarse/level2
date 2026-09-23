package io.securitycam.level2.detection

/**
 * Shared normalized-coordinate geometry for zones (0..1 space).
 *
 * Two directions exist and must stay inverses of each other:
 * - [rotateNorm]: analysis-frame coords → display coords for a display
 *   rotation (the monitor overlay and the zone editor draw path).
 * - [derotateNorm]: display coords → pre-rotation pixel coords (the
 *   privacy-mask export path). Implemented as rotation by `-degrees`
 *   (verified: derotate o rotate == identity for all four steps).
 */
object ZoneGeometry {

    /** Rotates normalized (x, y) for a display rotation in degrees (0/90/180/270). */
    fun rotateNorm(x: Double, y: Double, degrees: Int): Pair<Double, Double> = when (degrees) {
        90 -> (1.0 - y) to x
        270 -> y to (1.0 - x)
        180 -> (1.0 - x) to (1.0 - y)
        else -> x to y
    }

    /** Inverse of [rotateNorm]: display space back into pre-rotation pixels. */
    fun derotateNorm(x: Double, y: Double, degrees: Int): Pair<Double, Double> =
        rotateNorm(x, y, (360 - degrees) % 360)
}
