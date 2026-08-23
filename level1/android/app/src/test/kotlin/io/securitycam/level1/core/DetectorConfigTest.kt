package io.securitycam.level1.core

import io.securitycam.level1.detection.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Port of `test/detector_config_test.dart`. */
class DetectorConfigTest {

    @Test
    fun motionGatedDefaultsToFalse() {
        val c = DetectorConfig(type = "face")
        assertFalse(c.motionGated)
    }

    @Test
    fun motionGatedJsonRoundTrips() {
        val c = DetectorConfig(type = "face", motionGated = true)
        val back = DetectorConfig.fromJson(c.toJson())
        assertTrue(back.motionGated)
    }

    @Test
    fun missingMotionGatedFallsBackToFalse() {
        val back = DetectorConfig.fromJson(mapOf("type" to "face"))
        assertFalse(back.motionGated)
    }

    @Test
    fun defaultsMatchDart() {
        val c = DetectorConfig(type = "motion")
        assertEquals(true, c.enabled)
        assertEquals(0.5, c.threshold, 0.0)
        assertEquals(2, c.persistenceFrames)
        assertEquals(Duration.ofSeconds(5), c.cooldown)
        assertEquals(emptyList<String>(), c.routeToChannelIds)
        assertFalse(c.motionGated)
    }

    @Test
    fun jsonRoundTripsEveryField() {
        val c = DetectorConfig(
            type = "person",
            enabled = false,
            threshold = 0.3,
            persistenceFrames = 3,
            cooldown = Duration.ofSeconds(5),
            routeToChannelIds = listOf("telegram", "log"),
            motionGated = true,
        )
        val back = DetectorConfig.fromJson(c.toJson())
        assertEquals(c, back)
    }

    @Test
    fun unknownJsonFieldsAreTolerated() {
        val back = DetectorConfig.fromJson(mapOf("type" to "motion", "bogus" to 1))
        assertEquals("motion", back.type)
        assertEquals(0.5, back.threshold, 0.0)
    }
}