package io.securitycam.level1.detection.audio

import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.AudioWindow
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.sensors.AudioScene
import io.securitycam.level1.sensors.SimulatedAudioSource
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/audio_detectors_test.dart`. */
class AudioDetectorsTest {

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun scores(
        babyCry: Double = 0.0,
        glass: Double = 0.0,
        loudNoise: Double = 0.0,
    ): AudioEventScores = AudioEventScores(
        timestamp = base,
        classScores = mapOf("baby_cry" to babyCry, "glass" to glass, "loud_noise" to loudNoise),
    )

    @Test
    fun babyCryDoesNotTriggerBelowThreshold() {
        val detector = BabyCryDetector(
            DetectorConfig(type = TriggerType.babyCry, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(babyCry = 0.2))
        assertFalse(result.triggered)
    }

    @Test
    fun babyCryTriggersOncePersistenceIsMet() {
        val detector = BabyCryDetector(
            DetectorConfig(type = TriggerType.babyCry, threshold = 0.5, persistenceFrames = 2),
        )
        assertFalse(detector.analyzeScores(scores(babyCry = 0.8)).triggered)
        assertTrue(detector.analyzeScores(scores(babyCry = 0.8)).triggered)
    }

    @Test
    fun babyCryDoesNotReactToGlassScores() {
        val detector = BabyCryDetector(
            DetectorConfig(type = TriggerType.babyCry, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(glass = 0.9))
        assertFalse(result.triggered)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun glassBreakDoesNotTriggerOnBabyCryScores() {
        val detector = GlassBreakDetector(
            DetectorConfig(type = TriggerType.glassBreak, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(babyCry = 0.9))
        assertFalse(result.triggered)
    }

    @Test
    fun glassBreakTriggersOnGlassScore() {
        val detector = GlassBreakDetector(
            DetectorConfig(type = TriggerType.glassBreak, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(glass = 0.9))
        assertTrue(result.triggered)
        assertEquals(0.9, result.score, 0.0)
    }

    @Test
    fun loudNoiseDoesNotTriggerBelowThreshold() {
        val detector = LoudNoiseDetector(
            DetectorConfig(type = TriggerType.loudNoise, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(loudNoise = 0.2))
        assertFalse(result.triggered)
    }

    @Test
    fun loudNoiseTriggersOnLoudNoiseScore() {
        val detector = LoudNoiseDetector(
            DetectorConfig(type = TriggerType.loudNoise, threshold = 0.5, persistenceFrames = 1),
        )
        val result = detector.analyzeScores(scores(loudNoise = 0.9))
        assertTrue(result.triggered)
        assertEquals(0.9, result.score, 0.0)
    }

    @Test
    fun loudNoiseDoesNotReactToBabyCryOrGlassScores() {
        val detector = LoudNoiseDetector(
            DetectorConfig(type = TriggerType.loudNoise, threshold = 0.5, persistenceFrames = 1),
        )
        assertFalse(detector.analyzeScores(scores(babyCry = 0.9)).triggered)
        assertFalse(detector.analyzeScores(scores(glass = 0.9)).triggered)
    }

    @Test
    fun mockClassifierClassifiesBabyCryScene() = runBlocking {
        val classifier = MockAudioEventClassifier()
        classifier.init()
        val window = window(AudioScene.babyCry)
        val result = classifier.classify(window)
        assertTrue(result.scoreOf("baby_cry") > 0.5)
        assertTrue(result.scoreOf("glass") < 0.1)
        classifier.dispose()
    }

    @Test
    fun mockClassifierClassifiesGlassScene() = runBlocking {
        val classifier = MockAudioEventClassifier()
        val result = classifier.classify(window(AudioScene.glassBreak))
        assertTrue(result.scoreOf("glass") > 0.5)
        assertTrue(result.scoreOf("baby_cry") < 0.1)
        assertTrue(result.scoreOf("loud_noise") < 0.1)
    }

    @Test
    fun mockClassifierClassifiesBangScene() = runBlocking {
        val classifier = MockAudioEventClassifier()
        val result = classifier.classify(window(AudioScene.bang))
        assertTrue(result.scoreOf("loud_noise") > 0.5)
        assertTrue(result.scoreOf("baby_cry") < 0.1)
    }

    @Test
    fun mockClassifierClassifiesSilenceAsNoSignal() = runBlocking {
        val classifier = MockAudioEventClassifier()
        val result = classifier.classify(window(AudioScene.silence))
        assertTrue(result.scoreOf("baby_cry") < 0.1)
        assertTrue(result.scoreOf("glass") < 0.1)
        assertTrue(result.scoreOf("loud_noise") < 0.1)
    }

    private fun window(scene: AudioScene): AudioWindow = AudioWindow(
        timestamp = base,
        samples = SimulatedAudioSource.generateWindow(scene),
        sampleRate = SimulatedAudioSource.sampleRate,
    )
}