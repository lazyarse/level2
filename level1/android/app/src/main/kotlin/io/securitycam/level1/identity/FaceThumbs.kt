package io.securitycam.level1.identity

import android.graphics.Bitmap
import io.securitycam.level1.detection.ColorBitmap
import java.io.File
import kotlin.math.max

/**
 * Square face thumbnails cropped from analysis frames: pure geometry here,
 * JPEG encoding at the [writeJpg] edge so tests cover the math on the JVM.
 * Files live next to their centroids as `<id>.jpg`.
 */
object FaceThumbs {

    const val SIZE = 144

    /**
     * Square window centered on the normalized [box] (`x1,y1,x2,y2` in 0..1),
     * clamped to stay in-frame, nearest-neighbour downscaled to
     * `[size]x[size]` ARGB ints (row-major).
     */
    fun crop(frame: ColorBitmap, box: DoubleArray, size: Int = SIZE): IntArray {
        require(box.size >= 4) { "box needs x1,y1,x2,y2" }
        val w = frame.width
        val h = frame.height
        val px0 = (box[0] * w).toInt().coerceIn(0, w - 1)
        val py0 = (box[1] * h).toInt().coerceIn(0, h - 1)
        val px1 = (box[2] * w).toInt().coerceIn(px0 + 1, w)
        val py1 = (box[3] * h).toInt().coerceIn(py0 + 1, h)
        val pw = px1 - px0
        val ph = py1 - py0
        val side = max(pw, ph).toDouble()
        var cx = px0 + pw / 2.0
        var cy = py0 + ph / 2.0
        cx = cx.coerceIn(side / 2.0, w - side / 2.0)
        cy = cy.coerceIn(side / 2.0, h - side / 2.0)
        val sx0 = cx - side / 2.0
        val sy0 = cy - side / 2.0

        val out = IntArray(size * size)
        for (oy in 0 until size) {
            val y = (sy0 + (oy + 0.5) * side / size).toInt().coerceIn(0, h - 1)
            for (ox in 0 until size) {
                val x = (sx0 + (ox + 0.5) * side / size).toInt().coerceIn(0, w - 1)
                val idx = (y * w + x) * 3
                val b = frame.bgr[idx].toInt() and 0xFF
                val g = frame.bgr[idx + 1].toInt() and 0xFF
                val r = frame.bgr[idx + 2].toInt() and 0xFF
                out[oy * size + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Encodes the cropped thumbnail as JPEG into `<dir>/<id>.jpg`. */
    fun writeJpg(dir: File, id: String, frame: ColorBitmap, box: DoubleArray) {
        val pixels = crop(frame, box)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        dir.mkdirs()
        File(dir, "$id.jpg").outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bmp.recycle()
    }
}
