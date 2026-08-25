package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.GrayscaleBitmap
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Combined sight+sound semantics shared by [DogDetector] and [CatDetector]:
 * independent persistence per modality, separate visual/audio thresholds, and
 * modality details on fired events.
 */
class PetHybridDetectorTest {

    private val start: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private class FixedEngine(private val boxes: List<PersonBox>) : DogEngine, CatEngine {
        override suspend fun init() {}
        override suspend fun dispose() {}
        override suspend fun detectDogs(frame: ColorBitmap): List<PersonBox> = boxes
        override suspend fun detectCats(frame: ColorBitmap): List<PersonBox> = boxes
    }

    private fun scores(vararg pairs: Pair<String, Double>): AudioEventScores =
        AudioEventScores(
            timestamp = start,
            classScores = mapOf(*pairs),
        )

    private fun frame() = AnalysisFrame(
        timestamp = start,
        bitmap = GrayscaleBitmap(100, 100, ByteArray(100 * 100)),
        color = ColorBitmap(100, 100, ByteArray(100 * 100 * 3)),
    )

    private val visibleBox = listOf(PersonBox(0.0, 0.0, 50.0, 50.0, 0.9))

    @Test
    fun dogFiresOnSightWithSeenDetail() = runBlocking {
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.5, persistenceFrames = 2),
            FixedEngine(visibleBox),
        )
        assertFalse(d.analyzeFrameAsync(frame()).triggered)
        val fire = d.analyzeFrameAsync(frame())
        assertTrue(fire.triggered)
        assertEquals(DogDetector.DETAIL_SEEN, fire.detail)
    }

    @Test
    fun dogFiresOnBarkUsingAudioThresholdFallback() = runBlocking {
        // No audioThreshold set → falls back to the visual threshold (0.5).
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.5, persistenceFrames = 1),
            FixedEngine(emptyList()),
        )
        assertFalse(d.analyzeScores(scores("dog_bark" to 0.4)).triggered)
        val fire = d.analyzeScores(scores("dog_bark" to 0.6))
        assertTrue(fire.triggered)
        assertEquals(DogDetector.DETAIL_BARK, fire.detail)
    }

    @Test
    fun dogGrowlDetailWinsWhenLouderThanBark() {
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.5, persistenceFrames = 1),
            FixedEngine(emptyList()),
        )
        val fire = d.analyzeScores(scores("dog_bark" to 0.55, "growl" to 0.8))
        assertTrue(fire.triggered)
        assertEquals(DogDetector.DETAIL_GROWL, fire.detail)
    }

    @Test
    fun separateAudioThresholdGovernsSoundOnly() = runBlocking {
        val d = DogDetector(
            DetectorConfig(
                type = TriggerType.dog,
                threshold = 0.5,
                audioThreshold = 0.8,
                persistenceFrames = 1,
            ),
            FixedEngine(emptyList()),
        )
        // Below the dedicated audio threshold → no trigger even though it
        // would pass the visual one.
        assertFalse(d.analyzeScores(scores("dog_bark" to 0.6)).triggered)
        assertTrue(d.analyzeScores(scores("dog_bark" to 0.85)).triggered)
    }

    @Test
    fun modalitiesPersistIndependently() = runBlocking {
        var dogs: List<PersonBox> = emptyList()
        val engine = object : DogEngine {
            override suspend fun init() {}
            override suspend fun dispose() {}
            override suspend fun detectDogs(frame: ColorBitmap): List<PersonBox> = dogs
        }
        val d = DogDetector(
            DetectorConfig(type = TriggerType.dog, threshold = 0.5, persistenceFrames = 2),
            engine,
        )
        // Sound hit (streak 1/2), then a SIGHT miss must not reset the sound streak…
        d.analyzeScores(scores("dog_bark" to 0.9))
        assertFalse(d.analyzeFrameAsync(frame()).triggered) // sight miss, streak 1/2
        assertTrue(d.analyzeScores(scores("dog_bark" to 0.9)).triggered)
        // …and vice versa: a SOUND miss doesn't erase sight progress.
        dogs = visibleBox
        assertFalse(d.analyzeFrameAsync(frame()).triggered) // sight 1/2
        d.analyzeScores(scores("dog_bark" to 0.1))          // sound miss
        assertTrue(d.analyzeFrameAsync(frame()).triggered)  // sight still fires at 2/2
    }

    @Test
    fun catFiresOnMeowWithMeowDetail() {
        val d = CatDetector(
            DetectorConfig(type = TriggerType.cat, threshold = 0.5, persistenceFrames = 1),
            MockCatEngine(),
        )
        val fire = d.analyzeScores(scores("cat" to 0.7))
        assertTrue(fire.triggered)
        assertEquals(CatDetector.DETAIL_MEOW, fire.detail)
    }

    @Test
    fun catSightAndSoundAreSeparateCounters() = runBlocking {
        var cats: List<PersonBox> = emptyList()
        val engine = object : CatEngine {
            override suspend fun init() {}
            override suspend fun dispose() {}
            override suspend fun detectCats(frame: ColorBitmap): List<PersonBox> = cats
        }
        val d = CatDetector(
            DetectorConfig(type = TriggerType.cat, threshold = 0.5, persistenceFrames = 2),
            engine,
        )
        d.analyzeScores(scores("cat" to 0.9))          // sound streak 1/2
        cats = visibleBox
        assertFalse(d.analyzeFrameAsync(frame()).triggered) // sight streak 1/2
        assertTrue(d.analyzeFrameAsync(frame()).triggered)  // sight fires at 2/2
    }
}
