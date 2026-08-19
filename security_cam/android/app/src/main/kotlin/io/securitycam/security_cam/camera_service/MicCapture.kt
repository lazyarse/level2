package io.securitycam.security_cam.camera_service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Owns the single microphone [AudioRecord] for the monitoring FGS.
 *
 * One 16 kHz mono s16le AudioRecord feeds both the Dart analysis path (via the
 * PCM event bridge consumed by `NativeMicAudioSource`) and the clip recorder
 * (via `AudioMixSource` on API 31+). It is started before CameraX binds so the
 * recorder can hand the live AudioRecord to its audio mixer.
 */
class MicCapture {
    private val tag = "MicCapture"
    private val sampleRate = 16_000

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var running = false

    /** Live AudioRecord for the recorder's `AudioMixSource` (API 31+). */
    val record: AudioRecord? get() = audioRecord

    fun start(onPcm: (ByteArray) -> Unit) {
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
        readThread = Thread({
            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.w(tag, "startRecording failed", e)
                running = false
                return@Thread
            }
            val buf = ByteArray(bufferBytes)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                val exact = ByteArray(n)
                System.arraycopy(buf, 0, exact, 0, n)
                try {
                    onPcm(exact)
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