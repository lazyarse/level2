package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [birds] was pre-loaded with. */
class MockBirdEngine : BirdEngine {
    val birds = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectBirds(frame: ColorBitmap): List<DetectedBox> =
        birds.toList()

    override suspend fun dispose() {}
}
