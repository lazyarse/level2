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
    fun sameFrameBuildsInputAndRunsModelOnce() {
        val frame = frame()
        var builds = 0
        var runs = 0
        val first = YoloSharedInference.getOrRun(
            frame = frame,
            buildInput = {
                builds++
                floatArrayOf(1f)
            },
        ) {
            runs++
            floatArrayOf(2f)
        }
        val second = YoloSharedInference.getOrRun(
            frame = frame,
            buildInput = {
                builds++
                floatArrayOf(3f)
            },
        ) {
            runs++
            floatArrayOf(4f)
        }
        assertEquals(1, builds)
        assertEquals(1, runs)
        assertSame(first, second)
    }

    @Test
    fun newFrameBuildsInputAndRunsModelAgain() {
        var builds = 0
        var runs = 0
        repeat(2) {
            YoloSharedInference.getOrRun(
                frame = frame(),
                buildInput = {
                    builds++
                    floatArrayOf(1f)
                },
            ) {
                runs++
                floatArrayOf(2f)
            }
        }
        assertEquals(2, builds)
        assertEquals(2, runs)
    }
}
