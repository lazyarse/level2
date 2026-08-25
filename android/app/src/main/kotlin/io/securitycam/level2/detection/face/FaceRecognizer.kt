package io.securitycam.level2.detection.face

import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.identity.KnownFaceStore

/**
 * Face detector variant used while recognition is enabled: same detection
 * pass as [FaceDetector], but the top face is embedded and matched against
 * enrolled centroids, emitting `face_known` (detail = label) or
 * `face_unknown` instead of plain face events. Falls back to plain-face
 * behavior when the embedding model is unavailable or nobody is enrolled.
 */
class FaceRecognizer(
    override val config: DetectorConfig,
    private val store: KnownFaceStore,
    private val embedder: FaceEmbedder?,
    private val peopleProvider: () -> List<KnownFace>,
    /** Cosine-distance cutoff for a known match. */
    private val matchThreshold: Double,
    engine: FaceEngine? = null,
) : FaceDetector(config, engine) {

    private var persistenceCount = 0

    override fun reset() {
        super.reset()
        persistenceCount = 0
    }

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val top = topFace(frame) ?: run {
            persistenceCount = 0
            return result(frame.timestamp, 0.0, false)
        }
        val (color, best) = top
        val above = best.score >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        if (persistenceCount < config.persistenceFrames) {
            return result(frame.timestamp, best.score, false)
        }
        persistenceCount = 0

        return when (val identity = identify(color, best)) {
            null ->
                // No model / nobody enrolled: plain face behavior.
                result(frame.timestamp, best.score, true, triggerType = TriggerType.face)
            is Identity.Known ->
                result(
                    frame.timestamp,
                    best.score,
                    true,
                    triggerType = TriggerType.faceKnown,
                    detail = identity.person.label,
                    detectorId = TriggerType.faceKnown,
                )
            Identity.Unknown ->
                result(
                    frame.timestamp,
                    best.score,
                    true,
                    triggerType = TriggerType.faceUnknown,
                    detectorId = TriggerType.faceUnknown,
                )
        }
    }

    /** Match result of one frame's top face. */
    private sealed interface Identity {
        data class Known(val person: KnownFace) : Identity

        data object Unknown : Identity
    }

    /** Null when unidentifiable (no model / no enrollments / embedding failed). */
    private fun identify(color: io.securitycam.level2.detection.ColorBitmap, best: FaceDetection): Identity? {
        val embedder = embedder ?: return null
        val people = peopleProvider()
        if (people.isEmpty()) return null
        val raw = embedder.embed(color, doubleArrayOf(best.x1, best.y1, best.x2, best.y2))
            ?: return null
        val emb = KnownFaceStore.normalize(raw)
        var bestPerson: KnownFace? = null
        var bestDistance = Double.MAX_VALUE
        for (person in people) {
            val centroid = store.load(person.id) ?: continue
            val d = FaceEmbeddingEngine.cosineDistance(emb, centroid)
            if (d < bestDistance) {
                bestDistance = d
                bestPerson = person
            }
        }
        return bestPerson
            ?.takeIf { bestDistance <= matchThreshold }
            ?.let { Identity.Known(it) }
            ?: Identity.Unknown
    }

    private fun result(
        ts: java.time.Instant,
        score: Double,
        triggered: Boolean,
        triggerType: String = config.type,
        detail: String? = null,
        detectorId: String? = null,
    ): DetectionResult = DetectionResult(
        timestamp = ts,
        triggerType = triggerType,
        score = score,
        triggered = triggered,
        detail = detail,
        detectorId = detectorId,
    )
}
