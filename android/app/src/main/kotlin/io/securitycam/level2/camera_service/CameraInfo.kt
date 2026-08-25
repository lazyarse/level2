package io.securitycam.level2.camera_service

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Information about an available camera on the device. */
data class CameraInfo(
    val id: String,
    val label: String,
)

/** Enumerates all cameras available on this device. */
fun availableCameras(context: Context): List<CameraInfo> {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return manager.cameraIdList.map { id ->
        val chars = manager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val label = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Camera $id"
        }
        CameraInfo(id, label)
    }
}
