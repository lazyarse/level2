package io.securitycam.security_cam.camera_service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out bus for grayscale analysis frames produced by the camera foreground
 * service. The Flutter [CameraServiceChannels] EventChannel subscribes here so
 * analysis frames cross to the Dart isolate (160x120 @ ~4 fps during monitoring).
 */
object CameraFrameBus {
    private val listeners = CopyOnWriteArrayList<(gray: ByteArray, width: Int, height: Int) -> Unit>()

    fun add(listener: (gray: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (gray: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(gray: ByteArray, width: Int, height: Int) {
        listeners.forEach { it(gray, width, height) }
    }
}