package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test engine: returns whatever [boxes] was pre-loaded with. */
class FakeYoloEngine : YoloObjectEngine {
    val boxes = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detect(frame: ColorBitmap): List<DetectedBox> =
        boxes.toList()

    override suspend fun dispose() {}
}
