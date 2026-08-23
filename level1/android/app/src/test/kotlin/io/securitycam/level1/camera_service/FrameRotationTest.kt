package io.securitycam.level1.camera_service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Geometry tests for [FrameRotation] on tiny BGR frames (1 byte/px here). */
class FrameRotationTest {

    private fun px(bgr: ByteArray, w: Int, x: Int, y: Int): Byte = bgr[(y * w + x) * 3]

    /** 2x1 frame: [A B] */
    private fun rowAB(): Pair<ByteArray, Int> {
        val b = byteArrayOf(1, 0, 0, 2, 0, 0)
        return b to 2
    }

    @Test
    fun zeroDegreesIsIdentity() {
        val (b, w) = rowAB()
        val r = FrameRotation.rotate(b, w, 1, 0)
        assertEquals(2, r.width)
        assertEquals(1, r.height)
        assertArrayEquals(b, r.bgr)
    }

    @Test
    fun ninetyClockwiseTurnsRowIntoTopDownColumn() {
        val (b, w) = rowAB()
        val r = FrameRotation.rotate(b, w, 1, 90)
        assertEquals(1, r.width)
        assertEquals(2, r.height)
        assertEquals(1.toByte(), px(r.bgr, r.width, 0, 0)) // A on top
        assertEquals(2.toByte(), px(r.bgr, r.width, 0, 1)) // B below
    }

    @Test
    fun oneEightyReverses() {
        val (b, w) = rowAB()
        val r = FrameRotation.rotate(b, w, 1, 180)
        assertEquals(w, r.width)
        assertEquals(2.toByte(), px(r.bgr, r.width, 0, 0))
        assertEquals(1.toByte(), px(r.bgr, r.width, 1, 0))
    }

    @Test
    fun twoSeventyTurnsRowIntoBottomUpColumn() {
        val (b, w) = rowAB()
        val r = FrameRotation.rotate(b, w, 1, 270)
        assertEquals(1, r.width)
        assertEquals(2, r.height)
        assertEquals(2.toByte(), px(r.bgr, r.width, 0, 0)) // B on top
        assertEquals(1.toByte(), px(r.bgr, r.width, 0, 1))
    }

    @Test
    fun negativeAndOverFullTurnsNormalize() {
        val (b, w) = rowAB()
        // Identity cases: full turns.
        for (deg in listOf(-360, 360, 720)) {
            val r = FrameRotation.rotate(b, w, 1, deg)
            assertEquals("deg=$deg", 2, r.width)
            assertEquals(1.toByte(), px(r.bgr, r.width, 0, 0))
        }
        // -270 == +90: column with A on top.
        val r = FrameRotation.rotate(b, w, 1, -270)
        assertEquals(1, r.width)
        assertEquals(1.toByte(), px(r.bgr, r.width, 0, 0))
        assertEquals(2.toByte(), px(r.bgr, r.width, 0, 1))
    }
}
