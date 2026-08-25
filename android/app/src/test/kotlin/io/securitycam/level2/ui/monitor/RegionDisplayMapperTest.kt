package io.securitycam.level2.ui.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionDisplayMapperTest {

    private val frameAspect = 4f / 3f

    private fun assertNear(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>, eps: Float = 0.001f) {
        assertEquals(expectedX, actual.first, eps)
        assertEquals(expectedY, actual.second, eps)
    }

    private fun map(nx: Float, ny: Float, rotation: Int, w: Float, h: Float): Pair<Float, Float> {
        val o = RegionDisplayMapper.mapPoint(nx, ny, rotation, w, h, frameAspect)
        return o.x to o.y
    }

    @Test
    fun rotation0_landscape_view_mapsIdentically() {
        // 320x240 view matches the 4:3 frame aspect exactly — no letterbox.
        assertNear(0f, 0f, map(0f, 0f, 0, 320f, 240f))
        assertNear(320f, 240f, map(1f, 1f, 0, 320f, 240f))
        assertNear(160f, 120f, map(0.5f, 0.5f, 0, 320f, 240f))
    }

    @Test
    fun rotation0_letterboxesAspect() {
        // 400x400 view: 4:3 frame fits at 400x300, centered vertically.
        assertNear(0f, 50f, map(0f, 0f, 0, 400f, 400f))
        assertNear(400f, 350f, map(1f, 1f, 0, 400f, 400f))
    }

    @Test
    fun rotation90_portrait_rotatesAndCenters() {
        // Portrait 240x320: rotated frame aspect = 3/4 → fills 240x320 exactly.
        // Sensor (0,0) → display top-right.
        assertNear(240f, 0f, map(0f, 0f, 90, 240f, 320f))
        assertNear(0f, 320f, map(1f, 1f, 90, 240f, 320f))
        assertNear(120f, 160f, map(0.5f, 0.5f, 90, 240f, 320f))
    }

    @Test
    fun rotation270_portrait_rotatesOtherWay() {
        // Sensor (0,0) → display bottom-left.
        assertNear(0f, 320f, map(0f, 0f, 270, 240f, 320f))
        assertNear(240f, 0f, map(1f, 1f, 270, 240f, 320f))
        assertNear(120f, 160f, map(0.5f, 0.5f, 270, 240f, 320f))
    }

    @Test
    fun rotation180_keepsLandscapeAndFlips() {
        // 180° keeps the 4:3 frame landscape → letterboxed 240x180 centered in
        // the 240x320 view (y offset 70).
        assertNear(240f, 250f, map(0f, 0f, 180, 240f, 320f))
        assertNear(0f, 70f, map(1f, 1f, 180, 240f, 320f))
        assertNear(120f, 160f, map(0.5f, 0.5f, 180, 240f, 320f))
    }

    @Test
    fun rectRegion_rotatesCorrectly() {
        // Portrait 240x320, rect (0.25,0.25)-(0.75,0.75): 90° rotation maps the
        // 0.25..0.75 sensor band to the display middle column (x 60..180) and
        // top 0.25..0.75 of the height (y 80..240).
        assertNear(180f, 80f, map(0.25f, 0.25f, 90, 240f, 320f))
        assertNear(60f, 80f, map(0.25f, 0.75f, 90, 240f, 320f))
        assertNear(180f, 240f, map(0.75f, 0.25f, 90, 240f, 320f))
        assertNear(60f, 240f, map(0.75f, 0.75f, 90, 240f, 320f))
    }

    @Test
    fun polyVertices_mapToDisplay() {
        // Full-frame poly under rotation 0 in a 4:3 view maps corner-to-corner.
        assertNear(0f, 0f, map(0f, 0f, 0, 320f, 240f))
        assertNear(320f, 0f, map(1f, 0f, 0, 320f, 240f))
        assertNear(320f, 240f, map(1f, 1f, 0, 320f, 240f))
        assertNear(0f, 240f, map(0f, 1f, 0, 320f, 240f))
    }
}