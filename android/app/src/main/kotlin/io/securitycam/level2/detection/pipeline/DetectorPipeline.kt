package io.securitycam.level2.detection.pipeline

import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.core.TriggerEvent
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AudioDetector
import io.securitycam.level2.detection.AudioWindow
import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.Detector
import io.securitycam.level2.detection.DetectorRegistry
import io.securitycam.level2.detection.FrameDetector
import io.securitycam.level2.detection.HybridDetector
import io.securitycam.level2.detection.TripwireDetector
import io.securitycam.level2.detection.audio.AudioEventClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration
import java.time.Instant

/**
 * Runs all configured detectors over frames and audio windows (port of
 * `lib/detection/pipeline.dart`). Sync frame detectors run every frame;
 * motion-gated detectors run only when motion fires. Per-detector cooldown
 * suppresses repeat triggers; zone fans out to every frame detector.
 */
class DetectorPipeline(
    private val classifier: AudioEventClassifier,
    configs: List<DetectorConfig>,
    /**
     * Runtime-scoped factory lookup (Wave 4): [MonitoringRuntime] passes its
     * own [DetectorRegistry] so overlapping runtimes never share factories.
     * Defaults to the process-global registry for tests/legacy call sites.
     */
    private val registry: DetectorRegistry = DetectorRegistry.global,
) {
    private val frameDetectorsInternal: MutableList<FrameDetector> = configs
        .filter { it.enabled }
        .map { registry.factoryFor(it.type)?.invoke(it) }
        .filterIsInstance<FrameDetector>()
        .toMutableList()

    private val audioDetectorsInternal: MutableList<AudioDetector> = configs
        .filter { it.enabled }
        .map { registry.factoryFor(it.type)?.invoke(it) }
        .filterIsInstance<AudioDetector>()
        .toMutableList()

    private val lastTriggerAt = mutableMapOf<String, Instant>()
    private val triggerFlow = MutableSharedFlow<TriggerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val triggers: Flow<TriggerEvent> get() = triggerFlow.asSharedFlow()

    val frameDetectors: List<FrameDetector> get() = frameDetectorsInternal.toList()
    val audioDetectors: List<AudioDetector> get() = audioDetectorsInternal.toList()
    val frameDetectorCount: Int get() = frameDetectorsInternal.size
    val audioDetectorCount: Int get() = audioDetectorsInternal.size

    /** Test seam: injects an extra frame detector after construction. */
    fun debugAddFrameDetector(detector: FrameDetector) {
        frameDetectorsInternal.add(detector)
    }

    /** Sets the global inclusion/exclusion zones and fans them out to frame detectors. */
    fun setZones(
        zones: List<DetectionZone>,
        exclusionZones: List<DetectionZone> = emptyList(),
    ) {
        for (d in frameDetectorsInternal) {
            d.zones = zones
            d.exclusionZones = exclusionZones
        }
    }

    /** Sets tripwire zones on all TripwireDetector instances. */
    fun setTripwireZones(zones: List<DetectionZone>) {
        for (d in frameDetectorsInternal.filterIsInstance<TripwireDetector>()) {
            d.tripwireZones = zones
            d.sourceDetectors = frameDetectorsInternal.filter {
                it != d && matchesTripwireTarget(it, d.config.tripwireTargets)
            }
        }
    }

    private fun matchesTripwireTarget(detector: FrameDetector, targets: List<String>): Boolean {
        return targets.any { target ->
            when (target) {
                "person" -> detector.triggerType == TriggerType.person
                "vehicle" -> detector.triggerType == TriggerType.vehicle
                "dog" -> detector.triggerType == TriggerType.dog
                "cat" -> detector.triggerType == TriggerType.cat
                "bird" -> detector.triggerType == TriggerType.bird
                "livestock" -> detector.triggerType == TriggerType.livestock
                else -> false
            }
        }
    }

    suspend fun init() {
        classifier.init()
        for (d in frameDetectorsInternal) d.init()
        for (d in audioDetectorsInternal) d.init()
    }

    fun reset() {
        lastTriggerAt.clear()
        for (d in frameDetectorsInternal) d.reset()
        for (d in audioDetectorsInternal) d.reset()
    }

    /**
     * Motion gating is a fixed architectural rule, not a per-detector option:
     * every frame detector except motion itself (the gate source) and tamper
     * runs only on frames where motion fired, so expensive ML inference stays
     * asleep in quiet scenes. Tamper is exempt by necessity — it learns a
     * multi-frame baseline and its "moved"/"covered" paths specifically need
     * to run on still frames. Audio paths ([processAudio]) intentionally never
     * gate: sound is the complementary modality for what vision misses
     * (off-camera or static-scene events). [DetectorConfig.motionGated] is
     * legacy JSON ballast and is ignored here.
     */
    private fun isMotionGated(detector: FrameDetector): Boolean =
        detector.triggerType != TriggerType.motion &&
            detector.triggerType != TriggerType.tamper

    suspend fun processFrame(frame: io.securitycam.level2.detection.AnalysisFrame) {
        var motionFired = false
        for (d in frameDetectorsInternal) {
            if (isMotionGated(d)) continue
            val result = d.analyzeFrame(frame)
            if (result.triggered) {
                if (d.triggerType == TriggerType.motion) motionFired = true
                maybeEmit(d, result)
            }
        }
        if (!motionFired) return
        for (d in frameDetectorsInternal) {
            if (!isMotionGated(d)) continue
            val result = d.analyzeFrameAsync(frame)
            if (result.triggered) maybeEmit(d, result)
        }
    }

    /**
     * Audio windows are never motion-gated (see [isMotionGated]): standalone
     * audio detectors plus the score half of hybrid (combined pet) detectors
     * run on every window — the frame half runs gated in processFrame.
     */
    suspend fun processAudio(window: AudioWindow) {
        val scores = classifier.classify(window)
        // Standalone audio detectors plus the score half of hybrid (combined
        // pet) detectors — the frame half runs in processFrame.
        for (d in audioDetectorsInternal) {
            val result = d.analyzeScores(scores)
            if (result.triggered) maybeEmit(d, result)
        }
        for (d in frameDetectorsInternal.filterIsInstance<HybridDetector>()) {
            val result = d.analyzeScores(scores)
            if (result.triggered) maybeEmit(d, result)
        }
    }

    private fun maybeEmit(detector: Detector, result: io.securitycam.level2.detection.DetectionResult) {
        val last = lastTriggerAt[detector.id]
        val now = result.timestamp
        if (last != null && Duration.between(last, now) < detector.config.cooldown) return
        lastTriggerAt[detector.id] = now
        triggerFlow.tryEmit(
            TriggerEvent(
                timestamp = now,
                triggerType = result.triggerType,
                score = result.score,
                detectorId = result.detectorId ?: detector.id,
                detail = result.detail,
            ),
        )
    }

    /**
     * Public escape hatch for non-detector triggers (e.g. health events):
     * guarded by the shared cooldown map under [id], bypassing detectors.
     */
    fun emitTrigger(id: String, cooldown: Duration, event: TriggerEvent) {
        val last = lastTriggerAt[id]
        if (last != null && Duration.between(last, event.timestamp) < cooldown) return
        lastTriggerAt[id] = event.timestamp
        triggerFlow.tryEmit(event)
    }

    suspend fun dispose() {
        classifier.dispose()
        for (d in frameDetectorsInternal) d.dispose()
        for (d in audioDetectorsInternal) d.dispose()
    }
}