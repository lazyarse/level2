package io.securitycam.level2.detection

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `test/analysis_frame_test.dart`. */
class AnalysisFrameTest {

    @Test
    fun colorBitmapExposesPixelSafeBgrAccess() {
        val bgr = ColorBitmap(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(2, bgr.width)
        assertEquals(1, bgr.height)
        assertEquals(1, bgr.b(0, 0))
        assertEquals(2, bgr.g(0, 0))
        assertEquals(3, bgr.r(0, 0))
        assertEquals(4, bgr.b(1, 0))
        assertEquals(5, bgr.g(1, 0))
        assertEquals(6, bgr.r(1, 0))
    }

    @Test
    fun analysisFrameCarriesOptionalColorAndRequiredBitmap() {
        val frame = AnalysisFrame(
            timestamp = Instant.EPOCH,
            bitmap = GrayscaleBitmap(1, 1, byteArrayOf(10)),
            color = ColorBitmap(1, 1, byteArrayOf(5, 6, 7)),
        )
        assertEquals(10, frame.bitmap.pixel(0, 0))
        assertEquals(5, frame.color!!.b(0, 0))
    }
}