package io.securitycam.level2.detection.face

import io.securitycam.level2.detection.ColorBitmap

/** Test/dry-run engine: returns whatever [faces] was pre-loaded with. */
class MockFaceEngine : FaceEngine {
    val faces = mutableListOf<FaceDetection>()

    override suspend fun init() {}

    override suspend fun detectFaces(frame: ColorBitmap): List<FaceDetection> = faces.toList()

    override suspend fun dispose() {}
}
