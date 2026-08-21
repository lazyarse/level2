package io.securitycam.level1.detection.face

import io.securitycam.level1.detection.ColorBitmap

/** A detected face: bounding box (top-left x/y, bottom-right x/y) + score. */
data class FaceDetection(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val score: Double,
)

/** Abstraction over an on-device face detector (port of `face_engine.dart`). */
interface FaceEngine {
    suspend fun init()

    /** Returns detected faces in [frame]'s color bitmap. Empty list = no faces. */
    suspend fun detectFaces(frame: ColorBitmap): List<FaceDetection>

    suspend fun dispose()
}

/** Test/dry-run engine: returns whatever [faces] was pre-loaded with. */
class MockFaceEngine : FaceEngine {
    val faces = mutableListOf<FaceDetection>()

    override suspend fun init() {}

    override suspend fun detectFaces(frame: ColorBitmap): List<FaceDetection> = faces.toList()

    override suspend fun dispose() {}
}