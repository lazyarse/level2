package io.securitycam.level2.camera_service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kotlin observer API replacing the Flutter event channels. The Monitor screen
 * (via `MonitorViewModel`) subscribes here for mic PCM and preview availability;
 * [MonitoringServiceController] publishes. BGR analysis frames stay on
 * [CameraFrameBus].
 */
object CameraEvents {
    private val micPcmListeners = CopyOnWriteArrayList<(pcm: ByteArray, startSample: Long) -> Unit>()
    private val previewStatusListeners = CopyOnWriteArrayList<(active: Boolean) -> Unit>()

    fun addMicPcmListener(listener: (pcm: ByteArray, startSample: Long) -> Unit) {
        micPcmListeners.add(listener)
    }

    fun removeMicPcmListener(listener: (pcm: ByteArray, startSample: Long) -> Unit) {
        micPcmListeners.remove(listener)
    }

    fun addPreviewStatusListener(listener: (active: Boolean) -> Unit) {
        previewStatusListeners.add(listener)
    }

    fun removePreviewStatusListener(listener: (active: Boolean) -> Unit) {
        previewStatusListeners.remove(listener)
    }

    fun publishMicPcm(pcm: ByteArray, startSample: Long) {
        micPcmListeners.forEach { it(pcm, startSample) }
    }

    fun publishPreviewStatus(active: Boolean) {
        previewStatusListeners.forEach { it(active) }
    }
}