package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType

/**
 * Tripwire trigger: fires when a detected object's center crosses a tripwire
 * zone boundary. Reads boxes from matching source detectors — zero extra
 * YOLO inference. Stateful: tracks centers across frames via proximity.
 */
class TripwireDetector(
    override val config: DetectorConfig,
) : FrameDetector() {

    var tripwireZones: List<DetectionZone> = emptyList()

    /** Detectors to read boxes from (set by the pipeline). */
    var sourceDetectors: List<FrameDetector> = emptyList()

    private var previousCenters: MutableMap<Long, Pair<Double, Double>> = mutableMapOf()
    private var nextTrackId = 0L

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.tripwire

    override suspend fun init() {}

    override fun reset() {
        previousCenters.clear()
        nextTrackId = 0
    }

    override suspend fun dispose() {}

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val allBoxes = sourceDetectors.flatMap { it.latestBoxes }
        if (allBoxes.isEmpty() || tripwireZones.isEmpty()) {
            return result(frame.timestamp, 0.0, false)
        }

        val currentCenters = allBoxes.map { p ->
            val cx = (p.x1 + p.x2) / 2.0
            val cy = (p.y1 + p.y2) / 2.0
            Triple(cx, cy, p.score)
        }

        val matched = matchCenters(currentCenters)

        var maxScore = 0.0
        var crossed = false

        for (zone in tripwireZones) {
            val direction = zone.direction
            for ((trackId, curCenter) in matched) {
                val prevCenter = previousCenters[trackId] ?: continue
                val wasInside = ZoneFilter.pointInZone(zone, prevCenter.first, prevCenter.second)
                val isInside = ZoneFilter.pointInZone(zone, curCenter.first, curCenter.second)

                if (wasInside == isInside) continue

                val matchDirection = if (!wasInside && isInside) "in" else "out"
                if (direction == "either" || direction == matchDirection) {
                    val score = curCenter.third
                    if (score >= config.threshold) {
                        maxScore = maxOf(maxScore, score)
                        crossed = true
                    }
                }
            }
        }

        val newCenters = mutableMapOf<Long, Pair<Double, Double>>()
        for ((trackId, center) in matched) {
            newCenters[trackId] = center.first to center.second
        }
        previousCenters = newCenters

        return result(frame.timestamp, maxScore, crossed)
    }

    private fun matchCenters(
        current: List<Triple<Double, Double, Double>>,
    ): Map<Long, Triple<Double, Double, Double>> {
        val result = mutableMapOf<Long, Triple<Double, Double, Double>>()
        val usedCurrent = mutableSetOf<Int>()

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
        private const val MATCH_THRESHOLD = 0.15
    }
}
