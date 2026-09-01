package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.ZoneFilter.pointInZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Luminance + motion baseline tamper detector (port of the v1 design in
 * `docs/plans/2026-08-19-tamper-detection-design.md`). The first
 * [warmUpFrames] frames learn a baseline (mean luma μ/σ plus an 8×8 grid of
 * cell means, normalized 0..1). Afterwards:
 *
 *  - **covered** — sustained near-black frames (mean < max(0.03, μ−3σ));
 *  - **moved** — a persistent scene change (fraction of cells whose mean moved
 *    by more than [cellDelta] exceeds [config.threshold]) with low inter-frame
 *    motion (< [motionFloor]) ⇒ the camera was physically moved.
 *
 * Inclusion zones restrict which grid cells participate in the "moved"
 * comparison (empty = whole frame).
 */
class TamperDetector(
    override val config: DetectorConfig,
    private val warmUpFrames: Int = 60,
    private val motionFloor: Double = 0.01,
    private val cellDelta: Double = 0.08,
) : FrameDetector() {

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.tamper

    private var warmUpRemaining = warmUpFrames
    private var meanSum = 0.0
    private var meanSqSum = 0.0
    private var gridSum = DoubleArray(GRID * GRID)
    private var warmUpCount = 0

    private var mu = 0.0
    private var sigma = 0.0
    private var baselineGrid = DoubleArray(GRID * GRID)

    private var coveredCount = 0
    private var movedCount = 0
    private var previousGray: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    private var activeCells: BooleanArray? = null
    private var activeCellZones: List<DetectionZone>? = null
    private var activeCellWidth = 0
    private var activeCellHeight = 0

    override suspend fun init() {}

    override fun reset() {
        warmUpRemaining = warmUpFrames
        meanSum = 0.0
        meanSqSum = 0.0
        gridSum = DoubleArray(GRID * GRID)
        warmUpCount = 0
        mu = 0.0
        sigma = 0.0
        baselineGrid = DoubleArray(GRID * GRID)
        coveredCount = 0
        movedCount = 0
        previousGray = null
        activeCells = null
    }

    override suspend fun dispose() {}

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult {
        val bitmap = frame.bitmap
        if (!isArmed()) {
            learnBaseline(bitmap)
            previousGray = bitmap.gray.copyOf()
            previousWidth = bitmap.width
            previousHeight = bitmap.height
            return result(frame.timestamp, 0.0, false)
        }
        val mean = frameMean(bitmap)
        var triggered: DetectionResult? = maybeCovered(frame.timestamp, mean)
        if (triggered == null) {
            triggered = maybeMoved(frame.timestamp, bitmap)
        }
        previousGray = bitmap.gray.copyOf()
        previousWidth = bitmap.width
        previousHeight = bitmap.height
        return triggered ?: result(frame.timestamp, 0.0, false)
    }

    private fun isArmed(): Boolean {
        if (warmUpRemaining > 0) return false
        return true
    }

    private fun learnBaseline(bitmap: GrayscaleBitmap) {
        val mean = frameMean(bitmap)
        warmUpCount++
        meanSum += mean
        meanSqSum += mean * mean
        val grid = cellMeans(bitmap)
        for (i in gridSum.indices) gridSum[i] += grid[i]
        warmUpRemaining--
        if (warmUpRemaining == 0 && warmUpCount > 0) {
            mu = meanSum / warmUpCount
            val variance = max(0.0, meanSqSum / warmUpCount - mu * mu)
            sigma = sqrt(variance)
            for (i in baselineGrid.indices) {
                baselineGrid[i] = gridSum[i] / warmUpCount
            }
        }
    }

    /** Near-black sustained frames ⇒ camera covered. */
    private fun maybeCovered(ts: java.time.Instant, mean: Double): DetectionResult? {
        val blackFloor = max(0.03, mu - 3.0 * sigma)
        coveredCount = if (mean < blackFloor) coveredCount + 1 else 0
        if (coveredCount >= config.persistenceFrames && config.persistenceFrames > 0) {
            coveredCount = 0
            val score = if (mu <= 0.0) 1.0 else ((mu - mean) / mu).coerceIn(0.0, 1.0)
            return result(ts, score, true, detail = DETAIL_COVERED)
        }
        return null
    }

    /** Persistent cell change with low inter-frame motion ⇒ camera moved. */
    private fun maybeMoved(ts: java.time.Instant, bitmap: GrayscaleBitmap): DetectionResult? {
        val cellChange = changedCellFraction(bitmap)
        val interFrame = interFrameMotion(bitmap)
        val suspicious = cellChange >= config.threshold && interFrame < motionFloor
        movedCount = if (suspicious) movedCount + 1 else 0
        if (movedCount >= config.persistenceFrames && config.persistenceFrames > 0) {
            movedCount = 0
            return result(ts, cellChange, true, detail = DETAIL_MOVED)
        }
        return null
    }

    /** Fraction of active cells whose mean differs from baseline by > [cellDelta]. */
    private fun changedCellFraction(bitmap: GrayscaleBitmap): Double {
        val mask = activeCellMask(bitmap.width, bitmap.height)
        val grid = cellMeans(bitmap)
        var active = 0
        var changed = 0
        for (i in grid.indices) {
            if (!mask[i]) continue
            active++
            if (abs(grid[i] - baselineGrid[i]) > cellDelta) changed++
        }
        return if (active == 0) 0.0 else changed.toDouble() / active
    }

    /** Mean absolute pixel diff between consecutive frames, normalized to 0..1. */
    private fun interFrameMotion(bitmap: GrayscaleBitmap): Double {
        val prev = previousGray
        if (prev == null ||
            previousWidth != bitmap.width ||
            previousHeight != bitmap.height ||
            prev.size != bitmap.gray.size
        ) {
            return 1.0
        }
        var total = 0L
        val gray = bitmap.gray
        for (i in gray.indices) {
            total += abs((gray[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
        }
        return (total.toDouble() / gray.size) / 255.0
    }

    /** Grid of normalized cell means over [GRID]×[GRID] blocks. */
    private fun cellMeans(bitmap: GrayscaleBitmap): DoubleArray {
        val out = DoubleArray(GRID * GRID)
        val cellW = bitmap.width / GRID
        val cellH = bitmap.height / GRID
        if (cellW == 0 || cellH == 0) return out
        for (cy in 0 until GRID) {
            for (cx in 0 until GRID) {
                var sum = 0L
                var n = 0
                val yEnd = minOf(bitmap.height, (cy + 1) * cellH)
                val xEnd = minOf(bitmap.width, (cx + 1) * cellW)
                for (y in cy * cellH until yEnd) {
                    val row = y * bitmap.width
                    for (x in cx * cellW until xEnd) {
                        sum += bitmap.gray[row + x].toInt() and 0xFF
                        n++
                    }
                }
                out[cy * GRID + cx] = if (n == 0) 0.0 else (sum.toDouble() / n) / 255.0
            }
        }
        return out
    }

    private fun frameMean(bitmap: GrayscaleBitmap): Double {
        var sum = 0L
        val gray = bitmap.gray
        for (i in gray.indices) sum += gray[i].toInt() and 0xFF
        return if (gray.isEmpty()) 0.0 else (sum.toDouble() / gray.size) / 255.0
    }

    /**
     * Which grid cells count as trigger area: those whose center falls inside
     * an inclusion zone ([zones] empty = all cells).
     */
    private fun activeCellMask(width: Int, height: Int): BooleanArray {
        val cached = activeCells
        if (cached != null &&
            activeCellZones === zones &&
            activeCellWidth == width &&
            activeCellHeight == height
        ) {
            return cached
        }
        val mask = BooleanArray(GRID * GRID)
        if (zones.isEmpty()) {
            mask.fill(true)
        } else {
            for (cy in 0 until GRID) {
                for (cx in 0 until GRID) {
                    val nx = ((cx + 0.5) * width / GRID) / width
                    val ny = ((cy + 0.5) * height / GRID) / height
                    mask[cy * GRID + cx] =
                        zones.any { pointInZone(it, nx, ny) }
                }
            }
        }
        activeCells = mask
        activeCellZones = zones
        activeCellWidth = width
        activeCellHeight = height
        return mask
    }

    private fun result(
        ts: java.time.Instant,
        score: Double,
        triggered: Boolean,
        detail: String? = null,
    ): DetectionResult = DetectionResult(
        timestamp = ts,
        triggerType = triggerType,
        score = score,
        triggered = triggered,
        detail = detail,
    )

    companion object {
        const val GRID = 8
        const val DETAIL_COVERED = "covered"
        const val DETAIL_MOVED = "moved"
    }
}
