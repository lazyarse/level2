package io.securitycam.level1.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `test/pcm_window_accumulator_test.dart`. */
class PcmWindowAccumulatorTest {

    private fun s16(value: Int): ByteArray {
        val v = value.toShort().toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    private fun bytes(vararg parts: ByteArray): ByteArray = parts.reduce { a, b -> a + b }

    @Test
    fun decodesOneWindowOfS16leSamplesToFloat32() {
        val a = PcmWindowAccumulator(sampleRate = 16000, windowSamples = 4)
        val windows = a.add(bytes(s16(0), s16(16384), s16(32767), s16(-32768)))
        assertEquals(1, windows.size)
        val s = windows.single().samples
        assertEquals(0.0, s[0].toDouble(), 0.001)
        assertEquals(0.5, s[1].toDouble(), 0.001)
        assertEquals(0.99997, s[2].toDouble(), 0.0001)
        assertEquals(-1.0, s[3].toDouble(), 0.0)
        assertEquals(16000, windows.single().sampleRate)
        assertEquals(0, a.bufferedBytes)
    }

    @Test
    fun arbitraryByteSplitChunksStillAssembleFullWindows() {
        val a = PcmWindowAccumulator(sampleRate = 16000, windowSamples = 2)
        val all = bytes(s16(100), s16(200), s16(300), s16(400))
        val received = mutableListOf<io.securitycam.level1.detection.AudioWindow>()
        for (b in all) {
            received.addAll(a.add(byteArrayOf(b)))
        }
        assertEquals(2, received.size)
        assertEquals(100 / 32768.0, received[0].samples[0].toDouble(), 0.0001)
        assertEquals(200 / 32768.0, received[0].samples[1].toDouble(), 0.0001)
        assertEquals(300 / 32768.0, received[1].samples[0].toDouble(), 0.0001)
        assertEquals(0, a.bufferedBytes)
    }

    @Test
    fun oddLeftoverBytesAreCarriedAcrossCalls() {
        val a = PcmWindowAccumulator(sampleRate = 16000, windowSamples = 3)
        assertEquals(emptyList<Any>(), a.add(s16(11)))
        assertEquals(2, a.bufferedBytes)
        val windows = a.add(bytes(s16(22), s16(33)))
        assertEquals(1, windows.size)
        assertEquals(11 / 32768.0, windows.single().samples[0].toDouble(), 0.0001)
        assertEquals(33 / 32768.0, windows.single().samples[2].toDouble(), 0.0001)
        assertEquals(0, a.bufferedBytes)
    }
}