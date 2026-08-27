package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.person.PersonEngine
import io.securitycam.level2.detection.person.YoloPersonEngine
import io.securitycam.level2.detection.person.AppContextHolder

/**
 * Tripwire trigger: fires when a person's center crosses a tripwire region
 * boundary (outside→inside for "in", inside→outside for "out", or either
 * direction). Reuses the person engine's boxes — zero extra inference.
 * Stateful: tracks person centers across frames via proximity matching.
 */
class TripwireDetector(
    override val config: DetectorConfig,
    engine: PersonEngine? = null,
) : FrameDetector() {

    private val engine: PersonEngine = engine ?: YoloPersonEngine(AppContextHolder.require())

    /** Tripwire regions (separate from inclusion/exclusion). */
    var tripwireRegions: List<DetectionRegion> = emptyList()

    /** Previous person centers keyed by temporary tracking ID. */
    private var previousCenters: MutableMap<Long, Pair<Double, Double>> = mutableMapOf()
    private var nextTrackId = 0L

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.tripwire

    override suspend fun init() {
        engine.init()
    }

    override fun reset() {
        previousCenters.clear()
        nextTrackId = 0
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false)
        val people = engine.detectPersons(color)

        // Compute centers of detected people in normalized space.
        val currentCenters = people.map { p ->
            val cx = ((p.x1 + p.x2) / 2.0) / color.width
            val cy = ((p.y1 + p.y2) / 2.0) / color.height
            Triple(cx, cy, p.score)
        }

        // Match current centers to previous centers by proximity.
        val matched = matchCenters(currentCenters)
        val usedPrevIds = mutableSetOf<Long>()

        // Check for crossings in each tripwire region.
        var maxScore = 0.0
        var crossed = false

        for (region in tripwireRegions) {
            val direction = region.direction
            for ((trackId, curCenter) in matched) {
                val prevCenter = previousCenters[trackId] ?: continue
                val wasInside = RegionFilter.pointInRegion(region, prevCenter.first, prevCenter.second)
                val isInside = RegionFilter.pointInRegion(region, curCenter.first, curCenter.second)

                if (wasInside == isInside) continue

                val matchDirection = if (!wasInside && isInside) "in" else "out"
                if (direction == "either" || direction == matchDirection) {
                    val score = curCenter.third
                    if (score >= config.threshold) {
                        maxScore = maxOf(maxScore, score)
                        crossed = true
                    }
                }
                usedPrevIds.add(trackId)
            }
        }

        // Update previous centers with current matched positions.
        val newCenters = mutableMapOf<Long, Pair<Double, Double>>()
        for ((trackId, center) in matched) {
            newCenters[trackId] = center.first to center.second
        }
        previousCenters = newCenters

        return if (crossed) {
            result(frame.timestamp, maxScore, true)
        } else {
            result(frame.timestamp, maxScore, false)
        }
    }

    /**
     * Match current frame centers to previous frame centers by nearest proximity.
     * Returns map of track ID to current center triple (cx, cy, score).
     */
    private fun matchCenters(
        current: List<Triple<Double, Double, Double>>,
    ): Map<Long, Triple<Double, Double, Double>> {
        val result = mutableMapOf<Long, Triple<Double, Double, Double>>()
        val usedCurrent = mutableSetOf<Int>()

        // For each previous center, find the nearest unmatched current center.
        for ((trackId, prev) in previousCenters) {
            var bestDist = MATCH_THRESHOLD
            var bestIdx = -1
            for (i in current.indices) {
                if (i in usedCurrent) continue
                val (cx, cy, _) = current[i]
                val dx = cx - prev.first
                val dy = cy - prev.second
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                result[trackId] = current[bestIdx]
                usedCurrent.add(bestIdx)
            }
        }

        // Assign new IDs to unmatched current centers.
        for (i in current.indices) {
            if (i !in usedCurrent) {
                result[nextTrackId++] = current[i]
            }
        }

        return result
    }

    private fun result(ts: java.time.Instant, score: Double, triggered: Boolean): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
        )

    companion object {
        /** Maximum normalized distance to consider two centers as the same person. */
        private const val MATCH_THRESHOLD = 0.15
    }
}
