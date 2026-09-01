package io.securitycam.level2.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/region_filter_test.dart`. */
class ZoneFilterTest {

    private val rect = DetectionZone(
        id = "r1", shape = "rect", label = "doorway",
        points = listOf(0.1, 0.2, 0.5, 0.8),
    )

    private val poly = DetectionZone(
        id = "p1", shape = "poly", label = "driveway",
        points = listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8),
    )

    @Test
    fun rectContainsInteriorPoint() {
        assertTrue(ZoneFilter.pointInZone(rect, 0.3, 0.5))
    }

    @Test
    fun rectExcludesOutsidePoint() {
        assertFalse(ZoneFilter.pointInZone(rect, 0.7, 0.5))
        assertFalse(ZoneFilter.pointInZone(rect, 0.3, 0.9))
    }

    @Test
    fun rectBoundsAreInclusive() {
        assertTrue(ZoneFilter.pointInZone(rect, 0.1, 0.2))
        assertTrue(ZoneFilter.pointInZone(rect, 0.5, 0.8))
    }

    @Test
    fun convexPolyContainsInteriorPoint() {
        assertTrue(ZoneFilter.pointInZone(poly, 0.7, 0.45))
    }

    @Test
    fun polyExcludesOutsidePoint() {
        assertFalse(ZoneFilter.pointInZone(poly, 0.6, 0.1))
    }

    @Test
    fun concavePolyRayCasting() {
        // L-shaped poly: the notch area must be outside.
        val l = DetectionZone(
            id = "l1", shape = "poly", label = "L",
            points = listOf(0.2, 0.2, 0.8, 0.2, 0.8, 0.4, 0.5, 0.4, 0.5, 0.8, 0.2, 0.8),
        )
        assertTrue(ZoneFilter.pointInZone(l, 0.3, 0.6)) // inside the L
        assertFalse(ZoneFilter.pointInZone(l, 0.65, 0.6)) // in the notch
    }

    @Test
    fun emptyZonesAlwaysOverlap() {
        assertTrue(ZoneFilter.rectOverlapsAny(emptyList(), 0.0, 0.0, 0.1, 0.1))
    }

    @Test
    fun boxFullyInsideAZone() {
        assertTrue(ZoneFilter.rectOverlapsAny(listOf(rect), 0.2, 0.3, 0.1, 0.1))
    }

    @Test
    fun boxCrossingAZoneEdge() {
        assertTrue(ZoneFilter.rectOverlapsAny(listOf(rect), 0.45, 0.7, 0.1, 0.2))
    }

    @Test
    fun boxMerelyTouchingACornerCountsAsOverlap() {
        assertTrue(ZoneFilter.rectOverlapsAny(listOf(rect), 0.5, 0.8, 0.05, 0.05))
    }

    @Test
    fun boxOutsideAllZones() {
        assertFalse(ZoneFilter.rectOverlapsAny(listOf(rect), 0.7, 0.7, 0.1, 0.1))
    }

    @Test
    fun boxOverlappingAPolyZone() {
        assertTrue(ZoneFilter.rectOverlapsAny(listOf(poly), 0.6, 0.35, 0.1, 0.1))
    }

    @Test
    fun allOnesMaskForEmptyZonesPixelCountEqualsFullArea() {
        val (mask, count) = ZoneFilter.pixelMask(emptyList(), 4, 3)
        assertEquals(ByteArray(12) { 1 }.toList(), mask.toList())
        assertEquals(12, count)
    }

    @Test
    fun unionOfOverlappingZonesCountedOnce() {
        val a = DetectionZone("a", "rect", "a", listOf(0.0, 0.0, 0.5, 0.5))
        val b = DetectionZone("b", "rect", "b", listOf(0.25, 0.25, 0.75, 0.75))
        val (mask, count) = ZoneFilter.pixelMask(listOf(a, b), 4, 4)
        assertEquals(
            listOf<Byte>(1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0),
            mask.toList(),
        )
        assertEquals(7, count)
    }

    @Test
    fun pixelMaskExcludingWithoutExclusionsMatchesPixelMask() {
        val inclusions = listOf(rect, poly)
        val plain = ZoneFilter.pixelMask(inclusions, 8, 8)
        val excluding = ZoneFilter.pixelMaskExcluding(inclusions, emptyList(), 8, 8)
        assertEquals(plain.first.toList(), excluding.first.toList())
        assertEquals(plain.second, excluding.second)
    }

    @Test
    fun pixelMaskExcludingClearsPixelsInsideExclusion() {
        val inclusion = DetectionZone("i", "rect", "in", listOf(0.0, 0.0, 1.0, 1.0))
        val exclusion = DetectionZone("e", "rect", "out", listOf(0.4, 0.4, 0.6, 0.6))
        val (mask, count) = ZoneFilter.pixelMaskExcluding(listOf(inclusion), listOf(exclusion), 10, 10)
        // Pixel centers strictly inside the exclusion zone are cleared.
        for (y in 4..5) {
            for (x in 4..5) {
                assertEquals(0.toByte(), mask[y * 10 + x])
            }
        }
        // Centers just outside stay enabled.
        assertEquals(1.toByte(), mask[3 * 10 + 3])
        assertEquals(1.toByte(), mask[6 * 10 + 6])
        assertEquals(100 - 4, count)
    }

    @Test
    fun pixelMaskExcludingWithNoInclusionsStartsFromFullFrame() {
        val exclusion = DetectionZone("e", "rect", "out", listOf(0.0, 0.0, 0.5, 0.5))
        val (mask, count) = ZoneFilter.pixelMaskExcluding(emptyList(), listOf(exclusion), 4, 4)
        assertEquals(
            listOf<Byte>(0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            mask.toList(),
        )
        assertEquals(12, count)
    }

    @Test
    fun fullFrameExclusionYieldsEmptyMask() {
        val inclusion = DetectionZone("i", "rect", "in", listOf(0.0, 0.0, 1.0, 1.0))
        val exclusion = DetectionZone("e", "rect", "all", listOf(0.0, 0.0, 1.0, 1.0))
        val (mask, count) = ZoneFilter.pixelMaskExcluding(listOf(inclusion), listOf(exclusion), 6, 6)
        assertEquals(ByteArray(36).toList(), mask.toList())
        assertEquals(0, count)
    }
}