package io.securitycam.level1.camera_service

import android.graphics.Bitmap
import android.graphics.Canvas
import io.securitycam.level1.core.ClipStampPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimestampStampTest {

    private val wall = 1756138440000L

    @Test
    fun textIsDateSpaceTime24h() {
        val t = TimestampStamp.text(wall, includeCameraName = false, cameraName = "Hallway")
        assertTrue(t.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun cameraNamePrefixesWhenEnabled() {
        val t = TimestampStamp.text(wall, includeCameraName = true, cameraName = "Hallway")
        assertTrue(t.startsWith("Hallway  "))
        assertTrue(t.contains(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun blankCameraNameOmittedEvenWhenEnabled() {
        val without = TimestampStamp.text(wall, includeCameraName = true, cameraName = "")
        val baseline = TimestampStamp.text(wall, includeCameraName = false, cameraName = "")
        assertEquals(baseline, without)
    }

    @Test
    fun drawBottomRightDoesNotThrow() {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        TimestampStamp.draw(
            Canvas(bmp), wall,
            position = ClipStampPosition.bottomRight,
            width = 640, height = 480,
            includeCameraName = false, cameraName = "",
        )
    }

    @Test
    fun drawTopLeftDoesNotThrow() {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        TimestampStamp.draw(
            Canvas(bmp), wall,
            position = ClipStampPosition.topLeft,
            width = 640, height = 480,
            includeCameraName = true, cameraName = "Hallway",
        )
    }

    @Test
    fun drawWithCameraNameDoesNotThrow() {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        TimestampStamp.draw(
            Canvas(bmp), wall,
            position = ClipStampPosition.bottomRight,
            width = 1920, height = 1080,
            includeCameraName = true, cameraName = "Front Door",
        )
    }
}
