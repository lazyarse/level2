package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [dogs] was pre-loaded with. */
class MockDogEngine : DogEngine {
    val dogs = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectDogs(frame: ColorBitmap): List<DetectedBox> =
        dogs.toList()

    override suspend fun dispose() {}
}
