package io.securitycam.level2.identity

import android.graphics.Bitmap
import io.securitycam.level2.detection.ColorBitmap
import java.io.File

/**
 * Square face photos cropped from analysis frames: pure geometry here,
 * JPEG encoding at the [writeJpg] edge so tests cover the math on the JVM.
 * Files live next to their centroids as `<id>_<index>.jpg`.
 */
object FaceThumbs {

    const val SIZE = 144

    /** Gallery photo edge: large enough for the zoomable full view. */
    const val PHOTO_SIZE = 512

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
        val (sx0, sy0, side) =
            io.securitycam.level2.detection.face.FaceEmbeddingEngine.squareWindow(
                w, h, px0, py0, px1, py1,
            )

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

    /**
     * Horizontal mirror of square [size]x[size] ARGB ints (row-major).
     * Front-camera analysis frames are unmirrored sensor readouts while the
     * preview the user poses against is a mirrored selfie — mirroring the
     * *displayed* photo (only) keeps what they see consistent without
     * touching the embedding, which must match the unmirrored live pipeline.
     */
    fun mirror(src: IntArray, size: Int): IntArray {
        require(src.size == size * size) { "pixels must be size*size" }
        val out = IntArray(src.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[y * size + x] = src[y * size + (size - 1 - x)]
            }
        }
        return out
    }

    /** Encodes the cropped photo as JPEG into `<dir>/<id>_<index>.jpg`. */
    fun writeJpg(
        dir: File,
        id: String,
        index: Int,
        frame: ColorBitmap,
        box: DoubleArray,
        size: Int = PHOTO_SIZE,
        mirror: Boolean = false,
    ) {
        val cropped = crop(frame, box, size)
        val pixels = if (mirror) mirror(cropped, size) else cropped
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        dir.mkdirs()
        File(dir, "${id}_$index.jpg").outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bmp.recycle()
    }
}
