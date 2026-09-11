package io.securitycam.level2.ui.monitor

import io.securitycam.level2.detection.DetectionZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wave 2: FILL_CENTER crop math + reversed-rect normalization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoneDisplayMapperWave2Test {

    private val frameAspect = 4f / 3f

    @Test
    fun fillCropCenterCropsToFillTheView() {
        // 400x400 view, 4:3 frame: scale covers (400) → 533x400 centered.
        val o0 = ZoneDisplayMapper.mapPoint(0f, 0f, 0, 400f, 400f, frameAspect, fillCrop = true)
        assertEquals(-200f / 3f, o0.x, 0.01f)
        assertEquals(0f, o0.y, 0.01f)
        val o1 = ZoneDisplayMapper.mapPoint(1f, 1f, 0, 400f, 400f, frameAspect, fillCrop = true)
        assertEquals(400f + 200f / 3f, o1.x, 0.01f)
        assertEquals(400f, o1.y, 0.01f)
        // Center stays centered under both modes.
        val c = ZoneDisplayMapper.mapPoint(0.5f, 0.5f, 0, 400f, 400f, frameAspect, fillCrop = true)
        assertEquals(200f, c.x, 0.01f)
        assertEquals(200f, c.y, 0.01f)
    }

    @Test
    fun fitDefaultIsUnchangedLetterbox() {
        val o = ZoneDisplayMapper.mapPoint(0f, 0f, 0, 400f, 400f, frameAspect)
        assertEquals(0f, o.x, 0.001f)
        assertEquals(50f, o.y, 0.001f)
    }

    @Test
    fun reversedRectProducesTheSameBoundsAsOrdered() {
        val ordered = DetectionZone("z", "rect", "z", listOf(0.2, 0.2, 0.8, 0.8))
        val reversed = DetectionZone("z", "rect", "z", listOf(0.8, 0.8, 0.2, 0.2))
        val bOrdered = ZoneDisplayMapper.zonePath(ordered, 0, 320f, 240f).getBounds()
        val bReversed = ZoneDisplayMapper.zonePath(reversed, 0, 320f, 240f).getBounds()
        assertEquals(bOrdered.left, bReversed.left, 0.001f)
        assertEquals(bOrdered.top, bReversed.top, 0.001f)
        assertEquals(bOrdered.right, bReversed.right, 0.001f)
        assertEquals(bOrdered.bottom, bReversed.bottom, 0.001f)
        // And it is non-empty (the old Rect(p0, p1) drew nothing when reversed).
        assertEquals(0.6f * 320f, bReversed.width, 0.5f)
        assertEquals(0.6f * 240f, bReversed.height, 0.5f)
    }
}
