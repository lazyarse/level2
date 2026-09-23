package io.securitycam.level2.camera_service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/** PCM audio to mux alongside video: buffer, rate, and window start. */
data class MuxAudio(
    val pcm: AudioPcmBuffer,
    val sampleRate: Int,
    val startMicros: Long,
)

/**
 * Segment concatenation + AAC audio muxing. Split out of [VideoClipRecorder]:
 * pure functions of files, unit-testable without CameraX.
 */
object ClipMuxer {
    private const val TAG = "ClipMuxer"

    /**
     * Muxes the concatenated video segments plus (when [audio] maps a
     * non-empty PCM slice) an AAC audio track into [output]. The audio
     * timeline starts at 0 (aligned with the first video frame); the slice
     * runs from the pre-roll start over the full concatenated duration.
     * Falls back to video-only when audio is unavailable or the encode fails.
     */
    fun muxClip(
        inputs: List<File>,
        output: File,
        audio: MuxAudio?,
        orientationHintDegrees: Int,
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val totalVideoUs = videoDurationUs(inputs)
        var encoded: AacEncoder.AacFrames? = null
        if (audio != null && totalVideoUs > 0L) {
            val rate = audio.sampleRate
            val rawStartSample = audio.startMicros * rate / 1_000_000L
            val startSample = maxOf(0L, rawStartSample)
            val endSample = maxOf(startSample, (audio.startMicros + totalVideoUs) * rate / 1_000_000L)
            val prefixSamples = maxOf(0L, -rawStartSample)
            val slice = audio.pcm.slice(startSample, endSample)
            if (slice.isNotEmpty() || prefixSamples > 0L) {
                val prefixBytes = clampSamplesToBytes(prefixSamples)
                val combined = ByteArray(slice.size + prefixBytes)
                System.arraycopy(slice, 0, combined, prefixBytes, slice.size)
                val t1 = SystemClock.elapsedRealtime()
                encoded = try {
                    AacEncoder.encode(combined, rate)
                } catch (e: Exception) {
                    Log.w(TAG, "AAC encode failed; clip stays video-only", e)
                    null
                }
                Log.i(TAG, "aac encode: ${combined.size / 2} samples in ${SystemClock.elapsedRealtime() - t1}ms")
            }
        }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // Segment re-muxing strips the per-segment rotation metadata CameraX
        // wrote; re-apply the authored orientation so exports play upright.
        muxer.setOrientationHint(orientationHintDegrees)
        val aac = encoded
        var videoTrack = -1
        var audioTrack = -1
        try {
            videoTrack = addVideoTrack(muxer, inputs)
            if (aac != null && aac.frames.isNotEmpty() && audio != null) {
                val af = MediaFormat.createAudioFormat("audio/mp4a-latm", audio.sampleRate, 1)
                af.setByteBuffer("csd-0", aac.csd0)
                audioTrack = muxer.addTrack(af)
            }
            muxer.start()
            writeVideoSamples(muxer, videoTrack, inputs)
            if (audioTrack >= 0 && aac != null) {
                val info = MediaCodec.BufferInfo()
                for ((buf, pts) in aac.frames) {
                    info.offset = 0
                    info.size = buf.remaining()
                    info.presentationTimeUs = pts
                    info.flags = 0
                    muxer.writeSampleData(audioTrack, buf, info)
                }
            }
        } finally {
            if (videoTrack >= 0) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            muxer.release()
        }
        if (videoTrack < 0) throw IllegalStateException("no video track to concatenate")
        Log.i(TAG, "mux complete in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    /** Total real video duration (µs) across [inputs], via last-sample times. */
    private fun videoDurationUs(inputs: List<File>): Long {
        var totalUs = 0L
        for (input in inputs) {
            var firstSampleTimeUs = -1L
            var lastSampleTimeUs = -1L
            var sampleCount = 0L
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.path)
                var srcTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        srcTrack = i
                        break
                    }
                }
                if (srcTrack < 0) continue
                extractor.selectTrack(srcTrack)
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTime
                    lastSampleTimeUs = sampleTime
                    sampleCount++
                    extractor.advance()
                }
            } finally {
                extractor.release()
            }
            if (lastSampleTimeUs >= 0 && firstSampleTimeUs >= 0) {
                totalUs += concatSegmentDurationUs(firstSampleTimeUs, lastSampleTimeUs, sampleCount)
            } else {
                totalUs += probeDurationUs(input)
            }
        }
        return totalUs
    }

    /** Registers the video track from the first input that has one. */
    private fun addVideoTrack(muxer: MediaMuxer, inputs: List<File>): Int {
        for (input in inputs) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.path)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        return muxer.addTrack(fmt)
                    }
                }
            } finally {
                extractor.release()
            }
        }
        return -1
    }

    /** Concatenates the video tracks of [inputs], offsetting timestamps. */
    private fun writeVideoSamples(muxer: MediaMuxer, trackIndex: Int, inputs: List<File>) {
        var offsetUs = 0L
        for (input in inputs) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.path)
                var srcTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        srcTrack = i
                        break
                    }
                }
                if (srcTrack < 0) continue
                extractor.selectTrack(srcTrack)
                var firstSampleTimeUs = -1L
                var lastSampleTimeUs = -1L
                var sampleCount = 0L
                val buffer = ByteBuffer.allocate(256 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTime
                    lastSampleTimeUs = sampleTime
                    sampleCount++
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = sampleTime - firstSampleTimeUs.coerceAtLeast(0L) + offsetUs
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                offsetUs += if (lastSampleTimeUs >= 0 && firstSampleTimeUs >= 0) {
                    concatSegmentDurationUs(firstSampleTimeUs, lastSampleTimeUs, sampleCount)
                } else {
                    probeDurationUs(input)
                }
            } finally {
                extractor.release()
            }
        }
    }

    /** Fallback duration via container metadata when samples can't be read. */
    private fun probeDurationUs(input: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.path)
            (retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            retriever.release()
        }
    }

    /**
     * Segment duration from first/last PTS plus one mean frame interval.
     * Segments each start their own PTS timeline (often near 0 with
     * overlapping ranges), so summing raw last-sample times double-counts;
     * re-basing on (last − first + delta) keeps the concat monotonic.
     */
    internal fun concatSegmentDurationUs(firstUs: Long, lastUs: Long, sampleCount: Long): Long {
        if (lastUs < firstUs || sampleCount <= 0) return 0L
        val delta = if (sampleCount > 1) (lastUs - firstUs) / (sampleCount - 1) else 0L
        return (lastUs - firstUs) + delta.coerceAtLeast(0L)
    }

    /**
     * samples*2 clamped to Int range for ByteArray sizing (a multi-minute
     * window can never exceed it in practice, but a corrupt timeline must not
     * wrap to a negative size and crash the export thread).
     */
    internal fun clampSamplesToBytes(samples: Long): Int =
        (samples.coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
}
