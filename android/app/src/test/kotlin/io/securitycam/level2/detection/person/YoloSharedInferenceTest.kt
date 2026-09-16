package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class YoloSharedInferenceTest {

    @After
    fun clearCache() {
        YoloSharedInference.clear()
    }

    private fun frame(): ColorBitmap = ColorBitmap(2, 2, ByteArray(2 * 2 * 3))

    @Test
    fun sameFrameRunsModelOnce() {
        val frame = frame()
        var runs = 0
        val first = YoloSharedInference.getOrRun(frame) {
            runs++
            floatArrayOf(1f)
        }
        val second = YoloSharedInference.getOrRun(frame) {
            runs++
            floatArrayOf(2f)
        }
        assertEquals(1, runs)
        assertSame(first, second)
    }

    @Test
    fun newFrameRunsModelAgain() {
        var runs = 0
        YoloSharedInference.getOrRun(frame()) {
            runs++
            floatArrayOf(1f)
        }
        YoloSharedInference.getOrRun(frame()) {
            runs++
            floatArrayOf(2f)
        }
        assertEquals(2, runs)
    }
}
