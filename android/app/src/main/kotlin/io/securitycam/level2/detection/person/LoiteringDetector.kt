package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.FrameDetector
import io.securitycam.level2.detection.ZoneFilter

/**
 * Loitering trigger: fires when a person stays inside an inclusion zone for
 * at least [DetectorConfig.dwellSeconds]. Reuses the person engine's boxes —
 * zero extra inference. One alert per loiter episode; the dwell clock resets
 * only after the person has been absent longer than [ABSENCE_GRACE], so brief
 * occlusions don't restart it.
 */
class LoiteringDetector(
    override val config: DetectorConfig,
    engine: PersonEngine? = null,
) : FrameDetector() {

    private val engine: PersonEngine = engine ?: YoloPersonEngine(AppContextHolder.require())

    /** Cumulative qualified-presence time in ms. */
    private var presentMs = 0L
    private var lastPresentAt: Long = 0L
    private var hasSeen = false

    /** Timestamp of the first frame of the current absence, if any. */
    private var absentSince: Long? = null

    /** True while an episode has fired and the person hasn't left yet. */
    private var episodeActive = false

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.loitering

    override suspend fun init() {
        engine.init()
    }

    override fun reset() {
        presentMs = 0
        lastPresentAt = 0
        hasSeen = false
        absentSince = null
        episodeActive = false
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false, null)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false, null)
        var people = engine.detectPersons(color)
        if (people.isNotEmpty()) {
            people = people.filter { p ->
                val bx = p.x1 / color.width
                val by = p.y1 / color.height
                val bw = (p.x2 - p.x1) / color.width
                val bh = (p.y2 - p.y1) / color.height
                ZoneFilter.rectOverlapsAny(zones, bx, by, bw, bh) &&
                    !ZoneFilter.boxHitsAnyExclusion(exclusionZones, bx, by, bw, bh)
            }
        }
        val nowMs = frame.timestamp.toEpochMilli()

        if (people.isEmpty()) {
            if (absentSince == null) absentSince = nowMs
            if (nowMs - absentSince!! > ABSENCE_GRACE_MS) {
                // Person truly gone: wipe progress so the next arrival starts fresh.
                presentMs = 0
                episodeActive = false
            }
            return result(frame.timestamp, 0.0, false, null)
        }

        // Returning from an absence: within grace the clock pauses (no credit);
        // beyond it the visit already restarted.
        val resumedFromAbsence = absentSince != null
        val absenceLength = if (resumedFromAbsence) nowMs - absentSince!! else 0L
        absentSince = null
        if (resumedFromAbsence && absenceLength > ABSENCE_GRACE_MS) {
            presentMs = 0
            episodeActive = false
        }

        // Confidence gate first: weak boxes don't accumulate dwell time.
        val maxScore = people.maxOf { it.score }
        if (maxScore < config.threshold) {
            lastPresentAt = nowMs
            hasSeen = true
            return result(frame.timestamp, maxScore, false, null)
        }

        val delta = when {
            !hasSeen -> 0L                  // first sight: no elapsed time yet
            resumedFromAbsence -> 0L        // pause ended this frame; resume fresh
            else -> (nowMs - lastPresentAt).coerceIn(0L, MAX_FRAME_GAP_MS)
        }
        lastPresentAt = nowMs
        hasSeen = true
        presentMs += delta

        if (!episodeActive && presentMs >= config.dwellSeconds * 1000L) {
            episodeActive = true
            return result(frame.timestamp, maxScore, true, "loitered ${config.dwellSeconds}s")
        }
        return result(frame.timestamp, maxScore, false, null)
    }

    private fun result(
        ts: java.time.Instant,
        score: Double,
        triggered: Boolean,
        detail: String?,
    ): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
            detail = detail,
        )

    companion object {
        /** Absence shorter than this keeps the accumulated dwell clock. */
        const val ABSENCE_GRACE_MS = 3_000L

        /** Gaps larger than this (pipeline stall) don't count as presence. */
        const val MAX_FRAME_GAP_MS = 2_000L
    }
}
