package io.securitycam.level2.detection.face

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPipeFaceEngineTest {

    @Test
    fun pixelBoxDividesByFrameDimensions() {
        assertEquals(0.25, MediaPipeFaceEngine.normalized(80f, 320), 1e-9)
        assertEquals(0.5, MediaPipeFaceEngine.normalized(120f, 240), 1e-9)
        assertEquals(1.0, MediaPipeFaceEngine.normalized(320f, 320), 1e-9)
    }

    @Test
    fun outOfRangePixelsClampToUnitSquare() {
        assertEquals(0.0, MediaPipeFaceEngine.normalized(-15f, 240), 1e-9)
        assertEquals(1.0, MediaPipeFaceEngine.normalized(999f, 240), 1e-9)
    }

    @Test
    fun zeroDimensionYieldsZero() {
        assertEquals(0.0, MediaPipeFaceEngine.normalized(10f, 0), 1e-9)
    }
}
