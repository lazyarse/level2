package io.securitycam.level1.detection

import java.time.Instant

/** Grayscale bitmap (port of `lib/core/models.dart`). */
class GrayscaleBitmap(
    val width: Int,
    val height: Int,
    val gray: ByteArray,
) {
    init {
        require(gray.size == width * height) { "gray length must be width*height" }
    }

    fun pixel(x: Int, y: Int): Int = gray[y * width + x].toInt() and 0xFF
}

/** Raw interleaved BGR pixel buffer (CameraX YUV→BGR). */
class ColorBitmap(
    val width: Int,
    val height: Int,
    val bgr: ByteArray,
) {
    init {
        require(bgr.size == width * height * 3) { "bgr length must be width*height*3" }
    }

    fun b(x: Int, y: Int): Int = bgr[(y * width + x) * 3].toInt() and 0xFF
    fun g(x: Int, y: Int): Int = bgr[(y * width + x) * 3 + 1].toInt() and 0xFF
    fun r(x: Int, y: Int): Int = bgr[(y * width + x) * 3 + 2].toInt() and 0xFF

    /** Derives a luminance bitmap (BT.601) from the interleaved BGR bytes. */
    fun toGrayscale(): GrayscaleBitmap {
        val gray = ByteArray(width * height)
        for (i in 0 until width * height) {
            val b = bgr[i * 3].toInt() and 0xFF
            val g = bgr[i * 3 + 1].toInt() and 0xFF
            val r = bgr[i * 3 + 2].toInt() and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).roundToIntSafe()
        }
        return GrayscaleBitmap(width, height, gray)
    }
}

private fun Double.roundToIntSafe(): Byte {
    val v = kotlin.math.round(this).toInt().coerceIn(0, 255)
    return v.toByte()
}

/** A single analysis frame fed to frame detectors. */
data class AnalysisFrame(
    val timestamp: Instant,
    val bitmap: GrayscaleBitmap,
    val color: ColorBitmap? = null,
)

/** A 0.975 s audio window fed to audio detectors. */
data class AudioWindow(
    val timestamp: Instant,
    val samples: FloatArray,
    val sampleRate: Int,
) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}