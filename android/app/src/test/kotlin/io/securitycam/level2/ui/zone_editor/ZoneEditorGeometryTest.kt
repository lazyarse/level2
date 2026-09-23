package io.securitycam.level2.ui.zones

import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.DetectionZoneShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letterbox geometry for the zone editor: FIT_CENTER mapping between screen
 * space and frame-normalized detector coordinates.
 */
class ZoneEditorGeometryTest {

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

    // ---- hitGrabTarget (pixel-space handle resolution) ----

    private fun rectZone(id: String, points: List<Double>) = DetectionZone(
        id = id,
        shape = DetectionZoneShape.rect,
        label = id,
        points = points,
    )

    @Test
    fun grabFindsAllFourRectCorners() {
        // Exact-fit canvas: norm (0.2,0.2,0.8,0.8) → px (64,48,256,192).
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(rectZone("r0", listOf(0.2, 0.2, 0.8, 0.8)))
        assertEquals(ZoneGrab.RectCorner(0, 0), hitGrabTarget(zones, 64f, 48f, box, 24f))
        assertEquals(ZoneGrab.RectCorner(0, 1), hitGrabTarget(zones, 256f, 48f, box, 24f))
        assertEquals(ZoneGrab.RectCorner(0, 2), hitGrabTarget(zones, 256f, 192f, box, 24f))
        assertEquals(ZoneGrab.RectCorner(0, 3), hitGrabTarget(zones, 64f, 192f, box, 24f))
    }

    @Test
    fun grabInsideRectAwayFromHandlesIsBody() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(rectZone("r0", listOf(0.2, 0.2, 0.8, 0.8)))
        assertEquals(ZoneGrab.Body(0), hitGrabTarget(zones, 160f, 120f, box, 24f))
    }

    @Test
    fun grabOutsideEverythingIsEmpty() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(rectZone("r0", listOf(0.2, 0.2, 0.8, 0.8)))
        assertEquals(ZoneGrab.Empty, hitGrabTarget(zones, 10f, 10f, box, 24f))
    }

    @Test
    fun topmostZoneHandleWins() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(
            rectZone("r0", listOf(0.1, 0.1, 0.9, 0.9)),
            rectZone("r1", listOf(0.2, 0.2, 0.8, 0.8)),
        )
        // (64,48) is r1's corner 0 and inside r0's body: the handle wins.
        assertEquals(ZoneGrab.RectCorner(1, 0), hitGrabTarget(zones, 64f, 48f, box, 24f))
    }

    @Test
    fun grabFindsPolyVertices() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(
            DetectionZone(
                id = "p0",
                shape = DetectionZoneShape.poly,
                label = "tri",
                points = listOf(0.1, 0.1, 0.9, 0.1, 0.5, 0.9),
            ),
        )
        // Vertex 2 → px (160, 216).
        assertEquals(ZoneGrab.PolyVertex(0, 2), hitGrabTarget(zones, 160f, 216f, box, 24f))
    }

    @Test
    fun grabOutsideRadiusFallsThrough() {
        val box = fitCenterBox(320f, 240f, 320, 240)
        val zones = listOf(rectZone("r0", listOf(0.2, 0.2, 0.8, 0.8)))
        // (94,60) is ~32px from corner 0 (64,48): outside the 24px radius,
        // comfortably inside the body.
        val grab = hitGrabTarget(zones, 94f, 60f, box, 24f)
        assertTrue("expected Body but was $grab", grab is ZoneGrab.Body)
    }

    @Test
    fun grabAccountsForLetterboxOffset() {
        // Tall canvas: 4:3 frame width-limited, image vertically centered.
        val box = fitCenterBox(320f, 480f, 320, 240)
        assertTrue(box.offsetY > 0f)
        val zones = listOf(rectZone("r0", listOf(0.2, 0.2, 0.8, 0.8)))
        // Corner 0 sits at (64, offsetY + 48), not (64, 48).
        assertEquals(
            ZoneGrab.RectCorner(0, 0),
            hitGrabTarget(zones, 68f, box.offsetY + 52f, box, 24f),
        )
        // Letterbox taps clamp into the image edge — here (0.0125, 0.0),
        // outside the zone entirely.
        assertEquals(ZoneGrab.Empty, hitGrabTarget(zones, 4f, 4f, box, 24f))
    }
}
