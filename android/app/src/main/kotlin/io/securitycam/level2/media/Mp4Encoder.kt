package io.securitycam.level2.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware H.264 preview encoder (replaces the GIF/LZW preview path).
 *
 * Feeds [Frame.argb] preview frames to the on-device AVC encoder via the
 * codec's byte-buffer input (ARGB→I420 conversion happens CPU-side, pure
 * Kotlin and JVM-testable), then muxes the elementary stream into a silent
 * MP4 with [MediaMuxer]. There is no palette, no median cut, no LZW — the
 * entire ~350-line quantiser that previously hid the median-cut hang is gone.
 *
 * The encode itself is Android-framework (MediaCodec + MediaMuxer), so it is
 * exercised by the on-device integration suite rather than Robolectric.
 */
object Mp4Encoder {

    /** One preview frame, kept in the same shape the GIF path carried. */
    data class Frame(
        val delayMs: Int,
        val width: Int,
        val height: Int,
        val argb: IntArray,
    )

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FRAME_RATE = 5
    private const val I_FRAME_INTERVAL = 2
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Converts one ARGB frame to BT.601 full-range I420 (YUV 4:2:0 planar).
     * Pure function — no Android classes — unit-testable on the JVM.
     */
    internal fun argbToI420(frame: Frame): ByteArray {
        val w = frame.width
        val h = frame.height
        val out = ByteArray(w * h * 3 / 2)
        val yRow = ByteArray(w)
        var yOff = 0
        var uvOff = w * h
        var argbIdx = 0
        for (y in 0 until h) {
            val evenY = y and 1 == 0
            for (x in 0 until w) {
                val c = frame.argb[argbIdx++]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yy = clampBt601(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                yRow[x] = yy.toByte()
                if (evenY && (x and 1) == 0) {
                    val u = clampBt601(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                    val v = clampBt601(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                    out[uvOff] = u.toByte()
                    out[uvOff + 1] = v.toByte()
                    uvOff += 2
                }
            }
            System.arraycopy(yRow, 0, out, yOff, w)
            yOff += w
        }
        return out
    }

    private fun clampBt601(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Encodes [frames] to a silent H.264 MP4. Runs blocking on the caller's
     * thread — call from a background dispatcher with an outer withTimeout
     * guard (the caller does). Returns the raw MP4 bytes.
     */
    fun encode(frames: List<Frame>): ByteArray {
        require(frames.isNotEmpty()) { "Mp4Encoder.encode: empty frame list" }
        val first = frames.first()
        val width = first.width
        val height = first.height

        val tmp = File.createTempFile("preview", ".mp4")
        tmp.deleteOnExit()
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val codec = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, pickColorFormat(codec))
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        var trackIndex = -1
        var muxerStarted = false
        val eos = MediaCodec.BufferInfo()
        val stepUs = 1_000_000L / FRAME_RATE
        var ptsUs = 0L
        var frameIdx = 0
        var eosQueued = false
        var eosSeen = false

        try {
            while (!eosSeen) {
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(eos, 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (trackIndex < 0) trackIndex = muxer.addTrack(codec.outputFormat)
                            if (!muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val out = codec.getOutputBuffer(outIdx)
                            val isConfig = (eos.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (out != null && muxerStarted && !isConfig && eos.size > 0) {
                                out.position(eos.offset)
                                out.limit(eos.offset + eos.size)
                                muxer.writeSampleData(trackIndex, out, eos)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((eos.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosSeen = true
                        }
                        else -> break
                    }
                }
                if (eosSeen) break

                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        if (frameIdx < frames.size) {
                            val f = frames[frameIdx++]
                            val input = codec.getInputBuffer(inIdx)
                            val i420 = argbToI420(f)
                            if (input != null) {
                                input.clear()
                                input.put(i420)
                                codec.queueInputBuffer(inIdx, 0, i420.size, ptsUs, 0)
                            }
                            ptsUs += stepUs
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        val bytes = tmp.readBytes()
        tmp.delete()
        Log.i("Mp4Encoder", "encoded ${frames.size} frames -> ${bytes.size} bytes ${width}x${height}")
        return bytes
    }

    private fun pickColorFormat(codec: MediaCodec): Int {
        val caps = codec.codecInfo.getCapabilitiesForType(MIME)
        for (cf in caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return cf
        }
        for (cf in caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return cf
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }
}
