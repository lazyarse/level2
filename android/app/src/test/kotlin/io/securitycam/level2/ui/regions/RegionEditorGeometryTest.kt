package io.securitycam.level2.ui.regions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Letterbox geometry for the region editor: FIT_CENTER mapping between screen
 * space and frame-normalized detector coordinates.
 */
class RegionEditorGeometryTest {

    @Test
    fun wideCanvasPillarboxesTheFrame() {
        // 4:3 frame in a 2:1 canvas → height-limited, centered horizontally.
        val box = fitCenterBox(canvasW = 800f, canvasH = 400f, frameW = 320, frameH = 240)
        val gain = 400f / 240f                       // height-limited
        assertEquals(320f * gain, box.width, 1e-3f)  // 533.33
        assertEquals(4f / 3f, box.width / box.height, 1e-3f) // frame aspect kept
        assertEquals((800f - 320f * gain) / 2f, box.offsetX, 1e-3f)
        assertEquals(0f, box.offsetY, 1e-3f)
    }

    @Test
    fun tallCanvasLetterboxesTopAndBottom() {
        // Portrait phone: 1080x2000 canvas, landscape 4:3 frame.
        val box = fitCenterBox(1080f, 2000f, 640, 480)
        val gain = 1080f / 640f                      // width-limited
        assertEquals(0f, box.offsetX, 1e-3f)
        assertEquals(480f * gain, box.height, 1e-3f) // 810
        assertEquals((2000f - 480f * gain) / 2f, box.offsetY, 1e-3f) // 595
    }

    @Test
    fun matchingAspectsFillExactly() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        assertEquals(0f, box.offsetX, 1e-3f)
        assertEquals(0f, box.offsetY, 1e-3f)
        assertEquals(320f, box.width, 1e-3f)
        assertEquals(240f, box.height, 1e-3f)
    }

    @Test
    fun screenToNormRoundTripsThroughNormToScreen() {
        val box = fitCenterBox(800f, 400f, 320, 240)
        val (nx, ny) = screenToNorm(x = 500f, y = 100f, box)
        assertEquals((500f - box.offsetX) / box.width, nx.toFloat(), 1e-6f)
        assertEquals(0.25f, ny.toFloat(), 1e-6f)

        assertEquals(500f, normToScreen(nx, box.offsetX, box.width), 1e-3f)
        assertEquals(100f, normToScreen(ny, box.offsetY, box.height), 1e-3f)
    }

    @Test
    fun tapsOutsideTheImageClampIntoRange() {
        val box = fitCenterBox(800f, 400f, 320, 240) // image spans x∈[200,600]
        val (nxLeft, _) = screenToNorm(x = 50f, y = 200f, box)
        val (nxRight, _) = screenToNorm(x = 750f, y = 200f, box)
        assertEquals(0.0, nxLeft, 1e-9)
        assertEquals(1.0, nxRight, 1e-9)
    }

    @Test
    fun degenerateSizesFallBackToFullCanvas() {
        val box = fitCenterBox(0f, 400f, 320, 240)
        assertEquals(0f, box.offsetX, 1e-3f)
        assertEquals(400f, box.height, 1e-3f)
    }
}
