package io.securitycam.level1.camera_service

/**
 * Common output interface for encoded frames.
 * Implemented by LiveViewServer and LiveViewPushClient.
 */
interface LiveViewOutput {
    fun onVideoFrame(nalUnits: List<ByteArray>, timestampUs: Long, isKeyFrame: Boolean)
    fun onAudioFrame(aacFrame: ByteArray, timestampUs: Long)
}
