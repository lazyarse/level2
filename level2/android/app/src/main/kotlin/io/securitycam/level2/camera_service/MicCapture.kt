package io.securitycam.level2.camera_service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Owns the single microphone [AudioRecord] for the monitoring FGS.
 *
 * One 16 kHz mono s16le AudioRecord feeds the native analysis path (via
 * [CameraEvents]) and the clip recorder's PCM buffer (muxed into clips at
 * export time). It is started before CameraX binds so the recorder can reuse
 * the live AudioRecord.
 *
 * Each PCM chunk is delivered with the absolute sample index of its first frame
 * (`startSample`), so consumers can map PCM bytes to the mic timeline.
 */
class MicCapture {
    private val tag = "MicCapture"
    private val sampleRate = 16_000

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var running = false

    fun start(onPcm: (pcm: ByteArray, startSample: Long) -> Unit) {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(tag, "no valid min buffer size ($minBuf)")
            return
        }
        val bufferBytes = minBuf * 2
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            Log.w(tag, "AudioRecord create failed", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord not initialized")
            record.release()
            return
        }
        audioRecord = record
        running = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(tag, "startRecording failed", e)
            running = false
            audioRecord = null
            try {
                record.release()
            } catch (_: Exception) {
            }
            return
        }
        readThread = Thread({
            val buf = ByteArray(bufferBytes)
            var totalSamples = 0L
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                val samples = n / 2
                val startSample = totalSamples
                totalSamples += samples
                val exact = ByteArray(n)
                System.arraycopy(buf, 0, exact, 0, n)
                try {
                    onPcm(exact, startSample)
                } catch (e: Exception) {
                    Log.w(tag, "pcm callback failed", e)
                }
            }
        }, "mic-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        readThread?.interrupt()
        readThread = null
    }
}