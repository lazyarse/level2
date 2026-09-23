package io.securitycam.level2.core

import io.securitycam.level2.detection.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/** Port of `test/detector_config_test.dart`. */
class DetectorConfigTest {

    @Test
    fun legacyMotionGatedKeyIsIgnored() {
        // Blobs written before the flag was removed still carry the key;
        // it must parse without affecting the config.
        val back = DetectorConfig.fromJson(mapOf("type" to "face", "motionGated" to true))
        assertEquals("face", back.type)
        assertEquals(0.5, back.threshold, 0.0)
    }

    @Test
    fun defaultsMatchDart() {
        val c = DetectorConfig(type = "motion")
        assertEquals(true, c.enabled)
        assertEquals(0.5, c.threshold, 0.0)
        assertEquals(2, c.persistenceFrames)
        assertEquals(Duration.ofSeconds(5), c.cooldown)
    }

    @Test
    fun jsonRoundTripsEveryField() {
        val c = DetectorConfig(
            type = "person",
            enabled = false,
            threshold = 0.3,
            persistenceFrames = 3,
            cooldown = Duration.ofSeconds(5),
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

    @Test
    fun legacyRouteToChannelIdsKeyIsIgnored() {
        // Blobs written before per-detector routing was removed still carry
        // the key; it must parse without affecting the config.
        val back = DetectorConfig.fromJson(
            mapOf("type" to "motion", "routeToChannelIds" to listOf("telegram")),
        )
        assertEquals("motion", back.type)
        assertEquals(0.5, back.threshold, 0.0)
    }
}