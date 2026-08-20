package io.securitycam.level1.camera_service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out bus for BGR analysis frames produced by the camera foreground
 * service. The native pipeline (and later the analysis feed view) subscribes
 * here so analysis frames reach the app process (preset resolution @ ~4 fps
 * during monitoring).
 */
object CameraFrameBus {
    private val listeners = CopyOnWriteArrayList<(bgr: ByteArray, width: Int, height: Int) -> Unit>()

    fun add(listener: (bgr: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (bgr: ByteArray, width: Int, height: Int) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(bgr: ByteArray, width: Int, height: Int) {
        listeners.forEach { it(bgr, width, height) }
    }
}