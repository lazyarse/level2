package io.securitycam.level1.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Port of `test/media_naming_test.dart`. */
class MediaNamingTest {

    @Test
    fun formatsDateTimeCameraNameWithMillisecondUniqueness() {
        val name = mediaFileName(
            timestamp = LocalDateTime.of(2026, 8, 18, 10, 30, 0, 123_000_000),
            cameraName = "Hallway",
            extension = "jpg",
        )
        assertEquals("2026-08-18_10-30-00-123_Hallway.jpg", name)
    }

    @Test
    fun zeroPadsSingleDigitFields() {
        val name = mediaFileName(
            timestamp = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 6_000_000),
            cameraName = "Cam",
            extension = "mp4",
        )
        assertEquals("2026-01-02_03-04-05-006_Cam.mp4", name)
    }

    @Test
    fun sanitizesUnsafeCameraNameCharacters() {
        val name = mediaFileName(
            timestamp = LocalDateTime.of(2026, 8, 18, 10, 30, 0, 0),
            cameraName = "Front Door/1 (up)",
            extension = "jpg",
        )
        assertEquals("2026-08-18_10-30-00-000_Front_Door_1__up_.jpg", name)
        assertEquals(false, name.contains('/'))
        assertEquals(false, name.contains(' '))
    }

    @Test
    fun differentTimestampsWithinASecondStillYieldDistinctNames() {
        val a = mediaFileName(
            timestamp = LocalDateTime.of(2026, 8, 18, 10, 30, 0, 1_000_000),
            cameraName = "Hallway",
            extension = "mp4",
        )
        val b = mediaFileName(
            timestamp = LocalDateTime.of(2026, 8, 18, 10, 30, 0, 2_000_000),
            cameraName = "Hallway",
            extension = "mp4",
        )
        assertEquals(a != b, true)
    }
}