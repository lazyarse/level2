package io.securitycam.level2.camera_service

import androidx.camera.video.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoClipRecorderTest {

    @Test
    fun videoFileName_usesSharedDateTimeCameraScheme() {
        val name = VideoClipRecorder.videoFileName(1_700_000_000_000L, "Hallway")
        assertEquals("2023-11-14_22-13-20-000_Hallway.mp4", name)
    }

    @Test
    fun videoFileName_sanitizesCameraName() {
        val name = VideoClipRecorder.videoFileName(1_700_000_000_000L, "Front/Door ")
        assertEquals("2023-11-14_22-13-20-000_Front_Door_.mp4", name)
    }

    @Test
    fun videoFileName_millisecondsZeroPadded() {
        val name = VideoClipRecorder.videoFileName(1_700_000_000_123L, "Cam")
        assertEquals("2023-11-14_22-13-20-123_Cam.mp4", name)
    }

    @Test
    fun videoFileName_monthsAndDaysPadded() {
        // 2024-01-05 07:47:06.000 UTC (epoch 1704440826000)
        val name = VideoClipRecorder.videoFileName(1_704_440_826_000L, "Cam")
        assertEquals("2024-01-05_07-47-06-000_Cam.mp4", name)
    }

    @Test
    fun mapQuality_unknownValueFallsBackToLowest() {
        assertEquals(Quality.LOWEST, VideoClipRecorder.mapQuality("bogus"))
    }

    @Test
    fun mapQuality_highestMapsToHighest() {
        assertEquals(Quality.HIGHEST, VideoClipRecorder.mapQuality("highest"))
    }
}