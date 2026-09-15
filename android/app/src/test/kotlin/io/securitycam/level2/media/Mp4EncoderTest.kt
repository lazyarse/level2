package io.securitycam.level2.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [Mp4Encoder.argbToI420]: BT.601 full-range I420 layout
 * plus canonical black/white/red vectors. The MediaCodec/MediaMuxer stage is
 * Android-framework and covered by the on-device integration suite.
 */
class Mp4EncoderTest {

    private fun frame(w: Int, h: Int, argb: Int) =
        Mp4Encoder.Frame(delayMs = 200, width = w, height = h, argb = IntArray(w * h) { argb })

    @Test
    fun `output size is W x H x 3 over 2`() {
        val out = Mp4Encoder.argbToI420(frame(4, 4, -0x1000000))
        assertEquals(4 * 4 * 3 / 2, out.size)
    }

    @Test
    fun `black maps to Y16 UV128`() {
        // 2x2 black: Y plane 4x16, U plane 1x128, V plane 1x128.
        val out = Mp4Encoder.argbToI420(frame(2, 2, -0x1000000))
        assertArrayEquals(
            byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()),
            out,
        )
    }

    @Test
    fun `white maps to Y235 UV128`() {
        val out = Mp4Encoder.argbToI420(frame(2, 2, -0x1))
        assertArrayEquals(
            byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()),
            out,
        )
    }

    @Test
    fun `pure red maps to Y82 U90 V240`() {
        val out = Mp4Encoder.argbToI420(frame(2, 2, -0x10000))
        assertArrayEquals(
            byteArrayOf(82, 82, 82, 82, 90, 240.toByte()),
            out,
        )
    }

    @Test
    fun `chroma subsamples per 2x2 block`() {
        // Top-left red, rest black: U/V come from the block's top-left pixel.
        val argb = intArrayOf(-0x10000, -0x1000000, -0x1000000, -0x1000000)
        val f = Mp4Encoder.Frame(delayMs = 200, width = 2, height = 2, argb = argb)
        val out = Mp4Encoder.argbToI420(f)
        assertEquals(82, out[0].toInt() and 0xFF)
        assertEquals(16, out[1].toInt() and 0xFF)
        assertEquals(90, out[4].toInt() and 0xFF)
        assertEquals(240.toByte(), out[5])
    }
}
