package io.securitycam.level1.detection.audio

import io.securitycam.level1.detection.AudioWindow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/yamnet_audio_event_classifier_test.dart`. */
class YamnetClassifierTest {

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun scores521(): FloatArray = FloatArray(521)

    private fun window(samples: FloatArray): AudioWindow =
        AudioWindow(timestamp = base, samples = samples, sampleRate = 16000)

    @Test
    fun readsBabyCryFromClass20() {
        val scores = scores521()
        scores[YamnetClassifier.BABY_CRY_CLASS] = 0.83f
        val mapped = YamnetClassifier.scoresFromClasses(scores, FloatArray(15600))
        assertEquals(0.83, mapped["baby_cry"]!!, 1e-6)
    }

    @Test
    fun fusesGlassClassesByMax() {
        val scores = scores521()
        scores[435] = 0.2f
        scores[464] = 0.66f
        val mapped = YamnetClassifier.scoresFromClasses(scores, FloatArray(15600))
        assertEquals(0.66, mapped["glass"]!!, 1e-6)
    }

    @Test
    fun emptyZeroScoresMapToZeros() {
        val mapped = YamnetClassifier.scoresFromClasses(scores521(), FloatArray(15600))
        assertEquals(0.0, mapped["baby_cry"]!!, 0.0)
        assertEquals(0.0, mapped["glass"]!!, 0.0)
    }

    @Test
    fun loudNoiseRisesWithWaveformRms() {
        val loud = FloatArray(15600) { 0.9f }
        val quiet = FloatArray(15600) { 0.01f }
        val m1 = YamnetClassifier.scoresFromClasses(scores521(), loud)
        val m2 = YamnetClassifier.scoresFromClasses(scores521(), quiet)
        assertTrue(m1["loud_noise"]!! > m2["loud_noise"]!!)
    }

    @Test
    fun glassSurvivesClassIndexOutOfRange() {
        val scores = FloatArray(400)
        val mapped = YamnetClassifier.scoresFromClasses(scores, FloatArray(15600))
        assertEquals(0.0, mapped["glass"]!!, 0.0)
    }

    @Test
    fun int8InputWriteReadPreservesValuesWithinQuantizationError() {
        val samples = floatArrayOf(0.001f, 0.5f, 1.0f, 3.5f, 8.0f, -2.0f, 9.0f, 0.0f)
        val scale = 0.078125 // 1/12.8, typical int8 input scale
        val zeroPoint = 0
        val bytes = ByteArray(samples.size)
        YamnetClassifier.writeInput(bytes, samples, int8 = true, scale = scale, zeroPoint = zeroPoint)
        for (i in samples.indices) {
            val expected = Math.round(samples[i] / scale + zeroPoint)
            assertEquals("quantized value at $i", expected.toByte(), bytes[i])
        }
        val back = YamnetClassifier.readOutput(bytes, int8 = true, scale = scale, zeroPoint = zeroPoint)
        for (i in samples.indices) {
            assertEquals(samples[i].toDouble(), back[i].toDouble(), scale / 2 + 1e-6)
        }
    }

    @Test
    fun float32InputWriteReadIsExact() {
        val samples = floatArrayOf(0.001f, 0.5f, 3.5f, -8.0f, 12.0f)
        val bytes = ByteArray(samples.size * 4)
        YamnetClassifier.writeInput(bytes, samples, int8 = false, scale = 1.0, zeroPoint = 0)
        val back = YamnetClassifier.readOutput(bytes, int8 = false, scale = 1.0, zeroPoint = 0)
        assertTrue(back.contentEquals(samples))
    }

    @Test
    fun clampsInt8ValuesToRange() {
        val samples = floatArrayOf(1e6f, -1e6f)
        val scale = 0.1
        val bytes = ByteArray(samples.size)
        YamnetClassifier.writeInput(bytes, samples, int8 = true, scale = scale, zeroPoint = 0)
        assertEquals(127.toByte(), bytes[0])
        assertEquals((-128).toByte(), bytes[1])
    }

    @Test
    fun scoreOfFallsBackToZeroForUnknownLabels() {
        val scores = AudioEventScores(
            timestamp = base,
            classScores = mapOf("baby_cry" to 0.9),
        )
        assertEquals(0.9, scores.scoreOf("baby_cry"), 0.0)
        assertEquals(0.0, scores.scoreOf("glass"), 0.0)
    }
}