package io.securitycam.level1.sensors

import io.securitycam.level1.detection.AudioWindow
import java.time.Instant

/**
 * Accumulates raw 16 kHz s16le PCM byte chunks into [AudioWindow]s, converting
 * to Float32 samples (`sample / 32768`) and carrying any partial window across
 * chunk boundaries (port of `lib/sensors/pcm_window_accumulator.dart`).
 */
class PcmWindowAccumulator(
    val sampleRate: Int = 16000,
    val windowSamples: Int = 15600,
) {
    private val pending = ArrayDeque<Byte>()

    fun add(chunk: ByteArray): List<AudioWindow> {
        for (b in chunk) pending.add(b)
        val bytes = ByteArray(pending.size)
        var i = 0
        for (b in pending) bytes[i++] = b
        pending.clear()
        val windows = mutableListOf<AudioWindow>()
        var sampleOffset = 0
        while (bytes.size / 2 - sampleOffset >= windowSamples) {
            windows.add(
                AudioWindow(
                    timestamp = Instant.now(),
                    samples = decode(bytes, sampleOffset, windowSamples),
                    sampleRate = sampleRate,
                ),
            )
            sampleOffset += windowSamples
        }
        val consumedBytes = sampleOffset * 2
        if (consumedBytes < bytes.size) {
            for (b in bytes.copyOfRange(consumedBytes, bytes.size)) pending.add(b)
        }
        return windows
    }

    private fun decode(bytes: ByteArray, sampleOffset: Int, count: Int): FloatArray {
        val out = FloatArray(count)
        var p = sampleOffset * 2
        for (j in 0 until count) {
            val lo = bytes[p].toInt() and 0xFF
            val hi = bytes[p + 1].toInt() and 0xFF
            val v = (lo or (hi shl 8)).toShort().toInt()
            out[j] = v / 32768.0f
            p += 2
        }
        return out
    }

    val bufferedBytes: Int get() = pending.size
}