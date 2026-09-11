package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [cats] was pre-loaded with. */
class MockCatEngine : CatEngine {
    val cats = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectCats(frame: ColorBitmap): List<DetectedBox> =
        cats.toList()

    override suspend fun dispose() {}
}
