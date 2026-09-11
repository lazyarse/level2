package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Test/dry-run engine: returns whatever [vehicles] was pre-loaded with. */
class MockVehicleEngine : VehicleEngine {
    val vehicles = mutableListOf<DetectedBox>()

    override suspend fun init() {}

    override suspend fun detectVehicles(frame: ColorBitmap): List<DetectedBox> =
        vehicles.toList()

    override suspend fun dispose() {}
}
