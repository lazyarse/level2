package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [persons] was pre-loaded with. */
class MockPersonEngine : PersonEngine {
    val persons = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectPersons(frame: io.securitycam.level2.detection.ColorBitmap): List<DetectedBox> =
        persons.toList()

    override suspend fun dispose() {}
}
