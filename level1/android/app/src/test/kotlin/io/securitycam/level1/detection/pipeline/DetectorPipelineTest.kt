package io.securitycam.level1.detection.pipeline

import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.TriggerEvent
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.AudioWindow
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectionResult
import io.securitycam.level1.detection.FrameDetector
import io.securitycam.level1.detection.GrayscaleBitmap
import io.securitycam.level1.detection.MotionDetector
import io.securitycam.level1.detection.audio.MockAudioEventClassifier
import io.securitycam.level1.detection.buildFrame
import io.securitycam.level1.detection.buildFrameWithRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

/** Port of `test/pipeline_test.dart`. */
class DetectorPipelineTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")
    private val sampleRate = 16_000
    private val windowSamples = 15_600

    private fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())

    private fun build(
        motionThreshold: Double = 0.01,
        motionCooldown: Duration = Duration.ofSeconds(60),
        babyCryEnabled: Boolean = true,
        babyCooldown: Duration = Duration.ofSeconds(60),
    ): DetectorPipeline = DetectorPipeline(
        classifier = MockAudioEventClassifier(),
        configs = listOf(
            DetectorConfig(
                type = TriggerType.motion,
                enabled = true,
                threshold = motionThreshold,
                persistenceFrames = 1,
                cooldown = motionCooldown,
            ),
            DetectorConfig(
                type = TriggerType.babyCry,
                enabled = babyCryEnabled,
                threshold = 0.5,
                persistenceFrames = 1,
                cooldown = babyCooldown,
            ),
        ),
    )

    private fun babyCryWindow(timestamp: Instant): AudioWindow {
        val samples = FloatArray(windowSamples)
        val freq = 250.0
        for (i in 0 until windowSamples) {
            val t = i.toDouble() / sampleRate
            val mod = 0.7 + 0.3 * sin(2 * PI * 4 * t)
            samples[i] = (0.45 * mod * sin(2 * PI * freq * t)).toFloat()
        }
        return AudioWindow(timestamp, samples, sampleRate)
    }

    @Test
    fun motionTriggersFlowThroughThePipeline() = runBlocking {
        val scope = scope()
        val pipeline = build()
        pipeline.init()
        val events = mutableListOf<TriggerEvent>()
        val collector = scope.launch { pipeline.triggers.collect { events.add(it) } }
        yield()
        // First frame primes the motion detector.
        pipeline.processFrame(
            AnalysisFrame(base, GrayscaleBitmap(16, 16, buildFrame(16, 16, 140))),
        )
        pipeline.processFrame(
            AnalysisFrame(
                base.plusSeconds(1),
                GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
            ),
        )
        assertEquals(1, events.size)
        assertEquals(TriggerType.motion, events.first().triggerType)
        collector.cancel()
        pipeline.dispose()
    }

    @Test
    fun perDetectorCooldownSuppressesRepeatTriggers() = runBlocking {
        val scope = scope()
        val pipeline = build(motionCooldown = Duration.ofSeconds(60))
        pipeline.init()
        val events = mutableListOf<TriggerEvent>()
        val collector = scope.launch { pipeline.triggers.collect { events.add(it) } }
        yield()

        fun rect(x: Int, y: Int): GrayscaleBitmap =
            GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, x, y, 4, 4, 30))

        // Prime, then trigger at t0.
        pipeline.processFrame(AnalysisFrame(base, GrayscaleBitmap(16, 16, buildFrame(16, 16, 140))))
        pipeline.processFrame(AnalysisFrame(base, rect(2, 2)))
        assertEquals(1, events.size)

        // Within cooldown: next motion frame must not re-trigger.
        pipeline.processFrame(AnalysisFrame(base.plusSeconds(30), rect(6, 6)))
        assertEquals(1, events.size)

        // Outside cooldown: triggers again.
        pipeline.processFrame(AnalysisFrame(base.plusSeconds(61), rect(8, 8)))
        assertEquals(2, events.size)
        collector.cancel()
        pipeline.dispose()
    }

    @Test
    fun audioWindowsEmitBabyCryTriggers() = runBlocking {
        val scope = scope()
        val pipeline = build()
        pipeline.init()
        val events = mutableListOf<TriggerEvent>()
        val collector = scope.launch { pipeline.triggers.collect { events.add(it) } }
        yield()
        pipeline.processAudio(AudioWindow(base, FloatArray(windowSamples), sampleRate))
        // Silence: no trigger.
        assertEquals(0, events.size)
        pipeline.processAudio(babyCryWindow(base.plusSeconds(1)))
        assertEquals(1, events.size)
        assertEquals(TriggerType.babyCry, events.single().triggerType)
        collector.cancel()
        pipeline.dispose()
    }

    @Test
    fun disabledDetectorsAreNotInstantiated() = runBlocking {
        val pipeline = build(babyCryEnabled = false)
        pipeline.init()
        assertEquals(1, pipeline.frameDetectorCount)
        assertEquals(0, pipeline.audioDetectorCount)
        pipeline.dispose()
    }

    @Test
    fun gatedDetectorsRunOnlyWhenMotionFires() = runBlocking {
        val scope = scope()
        val stub = GatedStubDetector(
            DetectorConfig(type = "gated", enabled = true, motionGated = true, persistenceFrames = 1),
        )
        val pipeline = DetectorPipeline(
            classifier = MockAudioEventClassifier(),
            configs = listOf(
                DetectorConfig(
                    type = TriggerType.motion, enabled = true, threshold = 0.01,
                    persistenceFrames = 1,
                ),
            ),
        )
        pipeline.init()
        pipeline.debugAddFrameDetector(stub) // injected before subscribing
        val events = mutableListOf<TriggerEvent>()
        val collector = scope.launch { pipeline.triggers.collect { events.add(it) } }
        yield()

        // Prime the motion detector (no motion on frame 1).
        pipeline.processFrame(AnalysisFrame(base, GrayscaleBitmap(16, 16, buildFrame(16, 16, 140))))
        assertEquals(0, stub.asyncCalls)
        assertEquals(0, events.size)

        // Motion fires on frame 2 → gated detector runs.
        pipeline.processFrame(
            AnalysisFrame(
                base.plusSeconds(1),
                GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
            ),
        )
        assertEquals(1, stub.asyncCalls)
        assertTrue(events.map { it.triggerType }.contains("gated"))

        // No motion on frame 3 (identical to frame 2) → gated detector does not run again.
        pipeline.processFrame(
            AnalysisFrame(
                base.plusSeconds(2),
                GrayscaleBitmap(16, 16, buildFrameWithRect(16, 16, 140, 2, 2, 4, 4, 30)),
            ),
        )
        assertEquals(1, stub.asyncCalls)
        collector.cancel()
        pipeline.dispose()
    }

    @Test
    fun setRegionsFansOutToFrameDetectors() = runBlocking {
        val pipeline = DetectorPipeline(
            classifier = MockAudioEventClassifier(),
            configs = listOf(
                DetectorConfig(type = TriggerType.motion, enabled = true, threshold = 0.01),
            ),
        )
        pipeline.init()
        val motion = pipeline.frameDetectors.first()
        assertEquals(emptyList<DetectionRegion>(), motion.regions)

        val region = DetectionRegion("r1", "rect", "doorway", listOf(0.1, 0.2, 0.5, 0.8))
        pipeline.setRegions(listOf(region))
        assertEquals(listOf(region), motion.regions)
        assertEquals(emptyList<DetectionRegion>(), motion.exclusionRegions)

        val exclusion = DetectionRegion("e1", "rect", "privacy", listOf(0.6, 0.6, 0.9, 0.9))
        pipeline.setRegions(listOf(region), listOf(exclusion))
        assertEquals(listOf(region), motion.regions)
        assertEquals(listOf(exclusion), motion.exclusionRegions)
        pipeline.dispose()
    }
}

/** Gated stub detector: counts how often its async path is invoked. */
class GatedStubDetector(
    override val config: DetectorConfig,
) : FrameDetector() {
    var asyncCalls = 0

    override val id: String get() = "gated-stub"
    override val triggerType: String get() = "gated"

    override suspend fun init() {}

    override fun reset() {}

    override suspend fun dispose() {}

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        DetectionResult(frame.timestamp, triggerType, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        asyncCalls++
        return DetectionResult(frame.timestamp, triggerType, 1.0, true)
    }
}