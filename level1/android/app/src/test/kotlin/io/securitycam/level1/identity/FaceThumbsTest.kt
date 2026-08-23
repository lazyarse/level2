package io.securitycam.level1.identity

import io.securitycam.level1.detection.ColorBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry tests for [FaceThumbs.crop] (no Android graphics). */
class FaceThumbsTest {

    /** 100x100 frame, solid red (BGR byte order). */
    private fun solidFrame(
        w: Int = 100,
        h: Int = 100,
        b: Byte = 0,
        g: Byte = 0,
        r: Byte = 0xFF.toByte(),
    ) = ColorBitmap(w, h, ByteArray(3 * w * h) { i ->
        when (i % 3) {
            0 -> b
            1 -> g
            else -> r
        }
    })

    @Test
    fun cropProducesRequestedSize() {
        val out = FaceThumbs.crop(solidFrame(), doubleArrayOf(0.25, 0.25, 0.75, 0.75))
        assertEquals(FaceThumbs.SIZE * FaceThumbs.SIZE, out.size)
    }

    @Test
    fun cropPreservesColorChannels() {
        val out = FaceThumbs.crop(solidFrame(r = 0x80.toByte()), doubleArrayOf(0.2, 0.2, 0.8, 0.8))
        // ARGB: alpha set, red channel carries the sample.
        assertTrue(out.all { it == ((0xFF shl 24) or (0x80 shl 16)) })
    }

    @Test
    fun boxNearEdgeStaysInFrame() {
        // Box hugging the top-left corner must not push the square window out
        // of bounds (would throw AIOOBE during sampling).
        val out = FaceThumbs.crop(solidFrame(), doubleArrayOf(0.0, 0.0, 0.05, 0.05))
        assertEquals(FaceThumbs.SIZE * FaceThumbs.SIZE, out.size)
    }

    @Test
    fun wideBoxUsesSquareWindowCenteredOnBox() {
        val w = 200
        val frame = ColorBitmap(w, 100, ByteArray(3 * w * 100)) // black frame
        // Paint a white vertical stripe at x=150..160: a wide box centered at
        // x=100 with side=100 spans 50..150 and must NOT include it.
        for (y in 0 until 100) {
            for (x in 150 until 160) {
                val idx = (y * w + x) * 3
                frame.bgr[idx] = -1
                frame.bgr[idx + 1] = -1
                frame.bgr[idx + 2] = -1
            }
        }
        val out = FaceThumbs.crop(frame, doubleArrayOf(0.25, 0.25, 0.75, 0.75), size = 50)
        assertEquals(50 * 50, out.size)
        assertTrue(out.none { it == 0xFFFFFFFF.toInt() })
    }
}
