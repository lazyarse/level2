package io.securitycam.level2.camera_service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC-LC encoding for muxed clip audio. Split out of [VideoClipRecorder]:
 * pure function of PCM bytes, unit-testable without CameraX.
 */
object AacEncoder {
    private const val TAG = "AacEncoder"
    private const val AUDIO_FRAME_SAMPLES = 1024
    private const val AUDIO_BIT_RATE = 48_000

    /** AAC input dequeue timeout (µs): bounds the old zero-timeout busy-spin. */
    private const val INPUT_TIMEOUT_US = 10_000L

    /** Max EOS input retries before failing the encode instead of looping forever. */
    private const val MAX_EOS_SPINS = 500

    /** AAC-LC encoded audio track: decoder config plus (buffer, PTS) frames. */
    class AacFrames(
        val csd0: ByteBuffer,
        val frames: List<Pair<ByteBuffer, Long>>,
    )

    /** AAC-LC encoded frames for a mono s16le [pcm] slice, PTS from 0. */
    fun encode(pcm: ByteArray, sampleRate: Int): AacFrames {
        val frameBytes = AUDIO_FRAME_SAMPLES * 2
        val rate = sampleRate
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var csd0: ByteBuffer? = null
        val frames = ArrayList<Pair<ByteBuffer, Long>>()
        val info = MediaCodec.BufferInfo()
        val pcmBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val pad = ByteArray(frameBytes)
        var ptsFrame = 0L
        var feedIdx = 0
        val totalFrames = (pcm.size + frameBytes - 1) / frameBytes
        try {
            fun drain() {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIndex < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val asc = ByteBuffer.allocate(info.size)
                        asc.put(buf)
                        asc.flip()
                        csd0 = asc
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIndex, false)
                        return
                    }
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frame = ByteBuffer.allocate(info.size)
                        frame.put(buf)
                        frame.flip()
                        frames.add(frame to (ptsFrame * 1_000_000L * AUDIO_FRAME_SAMPLES / rate))
                        ptsFrame++
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            // Feed all frames with a bounded input timeout, draining
            // opportunistically; the encoder pipelines asynchronously. The old
            // dequeueInputBuffer(0) busy-spun when the codec was saturated.
            var starveSpins = 0
            while (feedIdx < totalFrames) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    starveSpins = 0
                    val input = codec.getInputBuffer(inputIndex)!!
                    input.clear()
                    val start = feedIdx * frameBytes
                    val len = minOf(frameBytes, pcm.size - start)
                    val src = pcmBuf.duplicate()
                    src.position(start)
                    src.limit(start + len)
                    input.put(src)
                    if (len < frameBytes) input.put(pad, 0, frameBytes - len)
                    codec.queueInputBuffer(inputIndex, 0, frameBytes, 0L, 0)
                    feedIdx++
                } else {
                    // Codec saturated: back off briefly instead of spinning.
                    if (++starveSpins % 50 == 0) {
                        Log.w(TAG, "AAC encoder input starved ($starveSpins spins)")
                    }
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("AAC encode interrupted")
                    }
                }
                drain()
            }
            var eosQueued = false
            var eosSpins = 0
            while (!eosQueued) {
                val eosIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (eosIndex >= 0) {
                    codec.queueInputBuffer(
                        eosIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                } else if (++eosSpins > MAX_EOS_SPINS) {
                    throw IllegalStateException("AAC encoder never freed an input for EOS")
                }
                drain()
            }
            drain()
            if (csd0 == null) throw IllegalStateException("AAC codec produced no config")
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
        return AacFrames(csd0!!, frames)
    }
}
