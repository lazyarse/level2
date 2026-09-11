package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [animals] was pre-loaded with. */
class MockLivestockEngine : LivestockEngine {
    val animals = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectLivestock(frame: ColorBitmap): List<DetectedBox> =
        animals.toList()

    override suspend fun dispose() {}
}
