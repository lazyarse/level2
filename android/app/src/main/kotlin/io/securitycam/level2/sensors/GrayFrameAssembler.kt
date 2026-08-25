package io.securitycam.level2.sensors

import io.securitycam.level2.detection.GrayscaleBitmap

/**
 * Accumulates raw grayscale byte chunks and emits whole frames, carrying any
 * remainder across chunk boundaries (port of
 * `lib/sensors/gray_frame_assembler.dart`).
 */
class GrayFrameAssembler(
    val width: Int,
    val height: Int,
) {
    val frameSize: Int = width * height
    private val pending = ArrayDeque<Byte>()

    init {
        require(width > 0 && height > 0) { "width/height must be positive" }
    }

    fun add(chunk: ByteArray): List<GrayscaleBitmap> {
        for (b in chunk) pending.add(b)
        val bytes = ByteArray(pending.size)
        var i = 0
        for (b in pending) bytes[i++] = b
        pending.clear()
        val frames = mutableListOf<GrayscaleBitmap>()
        var offset = 0
        while (bytes.size - offset >= frameSize) {
            frames.add(GrayscaleBitmap(width, height, bytes.copyOfRange(offset, offset + frameSize)))
            offset += frameSize
        }
        if (offset < bytes.size) {
            for (b in bytes.copyOfRange(offset, bytes.size)) pending.add(b)
        }
        return frames
    }

    val buffered: Int get() = pending.size
}