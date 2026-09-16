package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {

    companion object {
        private const val EPS = 1e-9
    }

    @Test
    fun endpointsMapToBandEdges() {
        // Classifier band: 0.10–0.90.
        assertEquals(
            0.90,
            SensitivityScale.sensitivityToThreshold(TriggerType.person, 1),
            EPS,
        )
        assertEquals(
            0.10,
            SensitivityScale.sensitivityToThreshold(TriggerType.person, 20),
            EPS,
        )
        // Motion band: 0.005–0.20.
        assertEquals(
            0.20,
            SensitivityScale.sensitivityToThreshold(TriggerType.motion, 1),
            EPS,
        )
        assertEquals(
            0.005,
            SensitivityScale.sensitivityToThreshold(TriggerType.motion, 20),
            EPS,
        )
    }

    @Test
    fun higherSensitivityMeansLowerThreshold() {
        for (type in listOf(TriggerType.person, TriggerType.motion, TriggerType.dog)) {
            var previous = Double.MAX_VALUE
            for (s in SensitivityScale.MIN..SensitivityScale.MAX) {
                val t = SensitivityScale.sensitivityToThreshold(type, s)
                assertTrue("type=$type s=$s", t < previous)
                previous = t
            }
        }
    }

    @Test
    fun roundTripIsStable() {
        for (type in listOf(TriggerType.person, TriggerType.motion, TriggerType.face)) {
            for (s in SensitivityScale.MIN..SensitivityScale.MAX) {
                val back = SensitivityScale.thresholdToSensitivity(
                    type,
                    SensitivityScale.sensitivityToThreshold(type, s),
                )
                assertEquals("type=$type s=$s", s, back)
            }
        }
    }

    @Test
    fun shippedDefaultsLandMidScale() {
        // Classifier default 0.5 → ~10–11; face 0.7 → ~6.
        assertEquals(
            11,
            SensitivityScale.thresholdToSensitivity(TriggerType.person, 0.5),
        )
        assertEquals(
            6,
            SensitivityScale.thresholdToSensitivity(TriggerType.face, 0.7),
        )
        // Motion default 0.03 → high end of its own band.
        assertEquals(
            18,
            SensitivityScale.thresholdToSensitivity(TriggerType.motion, 0.03),
        )
    }

    @Test
    fun outOfBandThresholdsClamp() {
        assertEquals(
            SensitivityScale.MIN,
            SensitivityScale.thresholdToSensitivity(TriggerType.person, 0.99),
        )
        assertEquals(
            SensitivityScale.MAX,
            SensitivityScale.thresholdToSensitivity(TriggerType.person, 0.01),
        )
        assertEquals(
            0.90,
            SensitivityScale.sensitivityToThreshold(TriggerType.person, 0),
            EPS,
        )
        assertEquals(
            0.10,
            SensitivityScale.sensitivityToThreshold(TriggerType.person, 99),
            EPS,
        )
    }

    @Test
    fun labelsBucketAsDocumented() {
        assertEquals("Low", SensitivityScale.label(1))
        assertEquals("Low", SensitivityScale.label(6))
        assertEquals("Medium", SensitivityScale.label(7))
        assertEquals("Medium", SensitivityScale.label(14))
        assertEquals("High", SensitivityScale.label(15))
        assertEquals("High", SensitivityScale.label(20))
    }
}
