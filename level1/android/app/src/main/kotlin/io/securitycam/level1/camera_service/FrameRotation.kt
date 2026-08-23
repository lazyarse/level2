package io.securitycam.level1.camera_service

/**
 * Pure BGR-frame rotation used to upright analysis frames before they hit the
 * bus: detectors, thumbnails and any future consumers all work on
 * display-upright pixels. Supports the 0/90/180/270 steps CameraX reports via
 * `ImageInfo.rotationDegrees` (how much to rotate clockwise for upright).
 */
data class RotatedBgr(val bgr: ByteArray, val width: Int, val height: Int)

object FrameRotation {

    fun rotate(bgr: ByteArray, width: Int, height: Int, degrees: Int): RotatedBgr =
        when (normalize(degrees)) {
            90 -> rot90(bgr, width, height)
            180 -> rot180(bgr, width, height)
            270 -> rot270(bgr, width, height)
            else -> RotatedBgr(bgr, width, height)
        }

    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** 90° clockwise: src(w x h) -> dst(h x w); dst[x'=h-1-y][y'=x]. */
    private fun rot90(bgr: ByteArray, w: Int, h: Int): RotatedBgr {
        val out = ByteArray(bgr.size)
        val dw = h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = (y * w + x) * 3
                val dst = (x * dw + (dw - 1 - y)) * 3
                out[dst] = bgr[src]
                out[dst + 1] = bgr[src + 1]
                out[dst + 2] = bgr[src + 2]
            }
        }
        return RotatedBgr(out, h, w)
    }

    /** 180°: reverse pixel order, dims unchanged. */
    private fun rot180(bgr: ByteArray, w: Int, h: Int): RotatedBgr {
        val out = ByteArray(bgr.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = (y * w + x) * 3
                val dst = ((h - 1 - y) * w + (w - 1 - x)) * 3
                out[dst] = bgr[src]
                out[dst + 1] = bgr[src + 1]
                out[dst + 2] = bgr[src + 2]
            }
        }
        return RotatedBgr(out, w, h)
    }

    /** 270° clockwise (= 90° CCW): src(w x h) -> dst(h x w); dst[x'=y][y'=w-1-x]. */
    private fun rot270(bgr: ByteArray, w: Int, h: Int): RotatedBgr {
        val out = ByteArray(bgr.size)
        val dw = h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = (y * w + x) * 3
                val dst = ((w - 1 - x) * dw + y) * 3
                out[dst] = bgr[src]
                out[dst + 1] = bgr[src + 1]
                out[dst + 2] = bgr[src + 2]
            }
        }
        return RotatedBgr(out, h, w)
    }
}
