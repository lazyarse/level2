package io.securitycam.level2.detection.face

import io.securitycam.level2.detection.ColorBitmap

/**
 * A detected face: bounding box (top-left x/y, bottom-right x/y) + score.
 * Coordinates are **normalized to 0..1** relative to the analyzed frame —
 * engines must convert before emitting (see MediaPipeFaceEngine.normalized).
 */
data class FaceDetection(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val score: Double,
)

/** Abstraction over an on-device face detector. */
interface FaceEngine {
    suspend fun init()

    /** Returns detected faces in [frame]'s color bitmap. Empty list = no faces. */
    suspend fun detectFaces(frame: ColorBitmap): List<FaceDetection>

    suspend fun dispose()
}