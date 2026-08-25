package io.securitycam.level2.detection

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `test/detection_region_test.dart`. */
class DetectionRegionTest {

    private val rect = DetectionRegion(
        id = "r1",
        shape = "rect",
        label = "doorway",
        points = listOf(0.1, 0.2, 0.5, 0.8),
    )

    @Test
    fun rectJsonRoundTrips() {
        val back = DetectionRegion.fromJson(rect.toJson())
        assertEquals("r1", back.id)
        assertEquals("rect", back.shape)
        assertEquals("doorway", back.label)
        assertEquals(listOf(0.1, 0.2, 0.5, 0.8), back.points)
    }

    @Test
    fun polyJsonRoundTrips() {
        val poly = DetectionRegion(
            id = "p1",
            shape = "poly",
            label = "driveway",
            points = listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8),
        )
        val back = DetectionRegion.fromJson(poly.toJson())
        assertEquals("poly", back.shape)
        assertEquals("driveway", back.label)
        assertEquals(listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8), back.points)
    }
}