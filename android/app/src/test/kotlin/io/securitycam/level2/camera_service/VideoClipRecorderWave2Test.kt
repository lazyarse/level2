package io.securitycam.level2.camera_service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wave 2: concat uses last+delta durations; byte casts are clamped. */
class VideoClipRecorderWave2Test {

    @Test
    fun segmentDurationAddsOneMeanFrameInterval() {
        // 5 samples spanning 0..4000us → mean interval 1000 → duration 5000.
        assertEquals(5000L, VideoClipRecorder.concatSegmentDurationUs(0L, 4000L, 5L))
    }

    @Test
    fun overlappingTimelinesDoNotDoubleCount() {
        // Two segments each starting near 0 with overlapping PTS: each
        // contributes (last − first + delta), not the raw last sum.
        val a = VideoClipRecorder.concatSegmentDurationUs(0L, 4000L, 5L)
        val b = VideoClipRecorder.concatSegmentDurationUs(500L, 4500L, 5L)
        assertEquals(5000L, a)
        assertEquals(5000L, b)
    }

    @Test
    fun singleSampleHasZeroDelta() {
        assertEquals(0L, VideoClipRecorder.concatSegmentDurationUs(1200L, 1200L, 1L))
    }

    @Test
    fun degenerateInputsYieldZero() {
        assertEquals(0L, VideoClipRecorder.concatSegmentDurationUs(5000L, 1000L, 4L))
        assertEquals(0L, VideoClipRecorder.concatSegmentDurationUs(0L, 0L, 0L))
    }

    @Test
    fun clampSamplesToBytesSaturatesInsteadOfOverflowing() {
        assertEquals(0, VideoClipRecorder.clampSamplesToBytes(-5L))
        assertEquals(200, VideoClipRecorder.clampSamplesToBytes(100L))
        assertEquals(Int.MAX_VALUE - 1, VideoClipRecorder.clampSamplesToBytes(Long.MAX_VALUE))
    }
}
