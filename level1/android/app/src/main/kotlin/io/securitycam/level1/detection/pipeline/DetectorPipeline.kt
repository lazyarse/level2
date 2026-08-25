package io.securitycam.level1.detection.pipeline

import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.TriggerEvent
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AudioDetector
import io.securitycam.level1.detection.AudioWindow
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.Detector
import io.securitycam.level1.detection.DetectorRegistry
import io.securitycam.level1.detection.FrameDetector
import io.securitycam.level1.detection.HybridDetector
import io.securitycam.level1.detection.audio.AudioEventClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration
import java.time.Instant

/**
 * Runs all configured detectors over frames and audio windows (port of
 * `lib/detection/pipeline.dart`). Sync frame detectors run every frame;
 * motion-gated detectors run only when motion fires. Per-detector cooldown
 * suppresses repeat triggers; region fans out to every frame detector.
 */
class DetectorPipeline(
    private val classifier: AudioEventClassifier,
    configs: List<DetectorConfig>,
) {
    private val frameDetectorsInternal: MutableList<FrameDetector> = configs
        .filter { it.enabled }
        .map { DetectorRegistry.factoryFor(it.type)?.invoke(it) }
        .filterIsInstance<FrameDetector>()
        .toMutableList()

    private val audioDetectorsInternal: MutableList<AudioDetector> = configs
        .filter { it.enabled }
        .map { DetectorRegistry.factoryFor(it.type)?.invoke(it) }
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

    /** Sets the global inclusion/exclusion regions and fans them out to frame detectors. */
    fun setRegions(
        regions: List<DetectionRegion>,
        exclusionRegions: List<DetectionRegion> = emptyList(),
    ) {
        for (d in frameDetectorsInternal) {
            d.regions = regions
            d.exclusionRegions = exclusionRegions
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

    suspend fun processFrame(frame: io.securitycam.level1.detection.AnalysisFrame) {
        var motionFired = false
        for (d in frameDetectorsInternal) {
            if (d.config.motionGated) continue
            val result = d.analyzeFrame(frame)
            if (result.triggered) {
                if (d.triggerType == TriggerType.motion) motionFired = true
                maybeEmit(d, result)
            }
        }
        if (!motionFired) return
        for (d in frameDetectorsInternal) {
            if (!d.config.motionGated) continue
            val result = d.analyzeFrameAsync(frame)
            if (result.triggered) maybeEmit(d, result)
        }
    }

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

    private fun maybeEmit(detector: Detector, result: io.securitycam.level1.detection.DetectionResult) {
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