package io.securitycam.level2.camera_service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streams PCM audio to the device speaker via AudioTrack. */
class SpeakerOutput {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun feedPcm(pcm: ByteArray) {
        track?.write(pcm, 0, pcm.size)
    }

    fun stop() {
        try {
            track?.stop()
        } catch (_: Exception) {}
        track?.release()
        track = null
    }

    companion object {
        const val SAMPLE_RATE = 8000
        val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
