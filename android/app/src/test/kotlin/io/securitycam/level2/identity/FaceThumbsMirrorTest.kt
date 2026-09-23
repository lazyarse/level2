package io.securitycam.level2.identity

import android.graphics.BitmapFactory
import android.graphics.Color
import io.securitycam.level2.detection.ColorBitmap
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end [FaceThumbs.writeJpg] mirror check: a half-dark frame must
 * decode with swapped sides when `mirror = true` (front-camera selfies).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceThumbsMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 100x100 frame: black left half, white right half (BGR). */
    private fun halfFrame(): ColorBitmap {
        val w = 100
        val h = 100
        val bgr = ByteArray(3 * w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v: Byte = if (x < w / 2) 0 else -1
                val idx = (y * w + x) * 3
                bgr[idx] = v
                bgr[idx + 1] = v
                bgr[idx + 2] = v
            }
        }
        return ColorBitmap(w, h, bgr)
    }

    private fun brightnessOf(file: File, leftHalf: Boolean): Int {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)!!
        val x = if (leftHalf) bmp.width / 4 else 3 * bmp.width / 4
        val p = bmp.getPixel(x, bmp.height / 2)
        return (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
    }

    @Test
    fun writeJpgMirrorSwapsSides() {
        val dir = tmp.newFolder()
        val box = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        FaceThumbs.writeJpg(dir, "p", 0, halfFrame(), box, size = 16)
        FaceThumbs.writeJpg(dir, "q", 0, halfFrame(), box, size = 16, mirror = true)
        val normal = File(dir, "p_0.jpg")
        val mirrored = File(dir, "q_0.jpg")
        assertTrue(normal.exists() && mirrored.exists())
        // Unmirrored: dark left, bright right.
        assertTrue(brightnessOf(normal, true) < 128)
        assertTrue(brightnessOf(normal, false) > 128)
        // Mirrored: bright left, dark right.
        assertTrue(brightnessOf(mirrored, true) > 128)
        assertTrue(brightnessOf(mirrored, false) < 128)
    }
}
