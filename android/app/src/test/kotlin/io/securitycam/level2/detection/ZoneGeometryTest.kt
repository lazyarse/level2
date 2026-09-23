package io.securitycam.level2.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneGeometryTest {

    private fun assertPair(
        expected: Pair<Double, Double>,
        actual: Pair<Double, Double>,
    ) {
        assertEquals(expected.first, actual.first, 1e-9)
        assertEquals(expected.second, actual.second, 1e-9)
    }

    @Test
    fun rotateNormMatchesDisplayConvention() {
        assertPair(0.2 to 0.3, ZoneGeometry.rotateNorm(0.2, 0.3, 0))
        assertPair(0.7 to 0.2, ZoneGeometry.rotateNorm(0.2, 0.3, 90))
        assertPair(0.8 to 0.7, ZoneGeometry.rotateNorm(0.2, 0.3, 180))
        assertPair(0.3 to 0.8, ZoneGeometry.rotateNorm(0.2, 0.3, 270))
    }

    @Test
    fun derotateInvertsRotateForAllSteps() {
        val points = listOf(0.0 to 0.0, 0.2 to 0.3, 1.0 to 1.0, 0.7 to 0.1)
        for (degrees in listOf(0, 90, 180, 270)) {
            for ((x, y) in points) {
                val (rx, ry) = ZoneGeometry.rotateNorm(x, y, degrees)
                assertPair(x to y, ZoneGeometry.derotateNorm(rx, ry, degrees))
            }
        }
    }

    @Test
    fun derotateMatchesPrivacyMaskTable() {
        // Pre-existing export convention, now derived instead of duplicated.
        assertPair(0.3 to 0.8, ZoneGeometry.derotateNorm(0.2, 0.3, 90))
        assertPair(0.8 to 0.7, ZoneGeometry.derotateNorm(0.2, 0.3, 180))
        assertPair(0.7 to 0.2, ZoneGeometry.derotateNorm(0.2, 0.3, 270))
    }
}
