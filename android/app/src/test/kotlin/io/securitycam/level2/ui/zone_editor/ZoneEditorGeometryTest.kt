package io.securitycam.level2.ui.zones

import io.securitycam.level2.ui.monitor.ZoneDisplayMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zone geometry through [ZoneDisplayMapper]: the editor converts touches via
 * [ZoneDisplayMapper.unmapPoint] and draws via [ZoneDisplayMapper.mapPoint],
 * both keyed on the capture rotation. R=0 reproduces the retired
 * fitCenterBox/screenToNorm math exactly (portrait behavior unchanged).
 */
class ZoneEditorGeometryTest {

    private fun legacyFitBox(
        canvasW: Float,
        canvasH: Float,
        frameW: Int,
        frameH: Int,
    ): Triple<Float, Float, Float> {
        // Retired fitCenterBox + per-axis extent, inlined for equivalence.
        if (canvasW <= 0f || canvasH <= 0f || frameW <= 0 || frameH <= 0) {
            return Triple(0f, 0f, 0f)
        }
        val gain = minOf(canvasW / frameW, canvasH / frameH)
        val w = frameW * gain
        val h = frameH * gain
        return Triple((canvasW - w) / 2f, (canvasH - h) / 2f, gain)
    }

    @Test
    fun zeroRotationMatchesLegacyFitMath() {
        val cases = listOf(
            Triple(800f, 400f, 320 to 240),
            Triple(1080f, 2000f, 640 to 480),
            Triple(320f, 240f, 320 to 240),
        )
        for ((cw, ch, wh) in cases) {
            val (fw, fh) = wh
            val aspect = fw.toFloat() / fh
            for (nx in listOf(0f, 0.25f, 0.5f, 1f)) {
                for (ny in listOf(0f, 0.33f, 0.75f, 1f)) {
                    // Draw direction: mapper == legacy box math.
                    val mapped = ZoneDisplayMapper.mapPoint(nx, ny, 0, cw, ch, aspect, fillCrop = false)
                    val (ox, oy, gain) = legacyFitBox(cw, ch, fw, fh)
                    assertEquals(ox + nx * fw * gain, mapped.x, 1e-3f)
                    assertEquals(oy + ny * fh * gain, mapped.y, 1e-3f)
                    // Touch direction: mapper inverts legacy math.
                    val unmapped = ZoneDisplayMapper.unmapPoint(
                        mapped.x, mapped.y, 0, cw, ch, aspect, fillCrop = false,
                    )
                    assertEquals(nx, unmapped.x, 1e-3f)
                    assertEquals(ny, unmapped.y, 1e-3f)
                }
            }
        }
    }

    @Test
    fun unmapInvertsMapForAllRotationsAndModes() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (fillCrop in listOf(false, true)) {
                for ((cw, ch) in listOf(800f to 400f, 1080f to 2000f, 320f to 240f)) {
                    for ((nx, ny) in listOf(0f to 0f, 0.2f to 0.7f, 0.6f to 0.3f, 1f to 1f)) {
                        val mapped = ZoneDisplayMapper.mapPoint(
                            nx, ny, rotation, cw, ch, 4f / 3f, fillCrop,
                        )
                        val back = ZoneDisplayMapper.unmapPoint(
                            mapped.x, mapped.y, rotation, cw, ch, 4f / 3f, fillCrop,
                        )
                        assertEquals("R=$rotation crop=$fillCrop x", nx, back.x, 1e-3f)
                        assertEquals("R=$rotation crop=$fillCrop y", ny, back.y, 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun tapsOutsideTheImageClampIntoRange() {
        // 800x400 canvas, 4:3 frame FIT → image spans x∈[133,667].
        val mapped = ZoneDisplayMapper.unmapPoint(50f, 200f, 0, 800f, 400f, 4f / 3f, fillCrop = false)
        assertEquals(0f, mapped.x, 1e-6f)
        val mappedRight = ZoneDisplayMapper.unmapPoint(750f, 200f, 0, 800f, 400f, 4f / 3f, fillCrop = false)
        assertEquals(1f, mappedRight.x, 1e-6f)
        assertTrue(mappedRight.y in 0f..1f)
    }
}
