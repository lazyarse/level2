package io.securitycam.level1.ui.regions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectionRegionShape
import io.securitycam.level1.detection.RegionFilter.pointInRegion

/** Which list the editor tools currently operate on. */
enum class RegionEditorMode { inclusion, exclusion }

/**
 * Interaction logic for the region editor. Port of the state machine in
 * `lib/ui/region_editor_screen.dart`: tap-to-select / poly-vertex placement,
 * drag-to-draw new rects, corner-resize and move of existing rects, label
 * editing, delete, clear — extended per the privacy-zones design to edit BOTH
 * the inclusion and exclusion lists through an active-mode toggle. All
 * coordinates are normalized (0..1) analysis-frame space; the composable only
 * converts pointer offsets.
 */
class RegionEditorViewModel(
    initialRegions: List<DetectionRegion>,
    initialExclusions: List<DetectionRegion> = emptyList(),
) {

    var inclusionRegions by mutableStateOf(initialRegions)
        private set
    var exclusionRegions by mutableStateOf(initialExclusions)
        private set
    var mode by mutableStateOf(RegionEditorMode.inclusion)
        private set

    /** The list the current [mode] edits; every tool below operates on it. */
    val regions: List<DetectionRegion>
        get() = if (mode == RegionEditorMode.inclusion) inclusionRegions else exclusionRegions

    var selected by mutableIntStateOf(-1)
        private set
    var shape by mutableStateOf(DetectionRegionShape.rect)
        private set
    var pendingPoly by mutableStateOf<List<Double>?>(null)
        private set
    var dragRect by mutableStateOf<List<Double>?>(null)
        private set

    private var nextId = 1
    private var dragStart: Pair<Double, Double>? = null
    private var dragLast: Pair<Double, Double>? = null
    private var dragResizing = false
    private var dragMoving = false

    fun chooseMode(value: RegionEditorMode) {
        if (mode == value) return
        mode = value
        selected = -1
        pendingPoly = null
        dragRect = null
    }

    private fun setActive(value: List<DetectionRegion>) {
        if (mode == RegionEditorMode.inclusion) {
            inclusionRegions = value
        } else {
            exclusionRegions = value
        }
    }

    fun select(index: Int) {
        selected = index
    }

    fun chooseShape(value: String) {
        shape = value
        if (value == DetectionRegionShape.rect) pendingPoly = null
    }

    /** Label edit; empty input keeps the previous label (Dart parity). */
    fun renameSelected(label: String) {
        val i = selected
        if (i !in regions.indices) return
        val r = regions[i]
        setActive(regions.toMutableList().also {
            it[i] = r.copy(label = label.trim().ifEmpty { r.label })
        })
    }

    fun onTap(nx: Double, ny: Double) {
        if (shape == DetectionRegionShape.poly) {
            // First tap in poly mode STARTS the pending polygon.
            val p = pendingPoly ?: emptyList()
            pendingPoly = p + listOf(nx, ny)
            return
        }
        select(hitRegion(nx, ny))
    }

    fun onPanStart(nx: Double, ny: Double) {
        val hit = hitRegion(nx, ny)
        if (hit >= 0) {
            select(hit)
            val r = regions[hit]
            if (r.shape == DetectionRegionShape.rect && nearCorner(r, nx, ny)) {
                dragResizing = true
            } else {
                dragMoving = true
            }
            // Anchor the first move delta at the grab point.
            dragLast = nx to ny
        } else if (shape == DetectionRegionShape.rect) {
            // Start a new rectangle at the drag origin.
            selected = -1
            pendingPoly = null
            dragStart = nx to ny
            dragLast = nx to ny
            dragRect = listOf(nx, ny, nx, ny)
        }
    }

    fun onPanUpdate(nx: Double, ny: Double) {
        val n = nx to ny
        when {
            dragResizing -> {
                val i = selected
                if (i in regions.indices) {
                    val r = regions[i]
                    setActive(regions.toMutableList().also {
                        it[i] = r.copy(points = listOf(r.points[0], r.points[1], nx.toDouble(), ny.toDouble()))
                    })
                }
            }
            dragMoving -> {
                val i = selected
                val last = dragLast
                if (i in regions.indices && last != null) {
                    val r = regions[i]
                    setActive(regions.toMutableList().also {
                        it[i] = r.copy(points = translate(r.points, nx - last.first, ny - last.second))
                    })
                    dragLast = n
                }
            }
            else -> {
                val start = dragStart
                if (dragRect != null && start != null) {
                    dragLast = n
                    dragRect = listOf(
                        minOf(start.first, nx),
                        minOf(start.second, ny),
                        maxOf(start.first, nx),
                        maxOf(start.second, ny),
                    )
                }
            }
        }
    }

    fun onPanEnd() {
        val rect = dragRect
        dragStart = null
        dragLast = null
        dragResizing = false
        dragMoving = false
        dragRect = null
        // Commit the newly drawn rectangle (skipped for tiny drags).
        if (rect != null &&
            kotlin.math.abs(rect[2] - rect[0]) >= 0.02 &&
            kotlin.math.abs(rect[3] - rect[1]) >= 0.02
        ) {
            addRegion(DetectionRegionShape.rect, rect)
        }
    }

    fun addRegion() {
        pendingPoly = null
        addRegion(DetectionRegionShape.rect, listOf(0.2, 0.2, 0.8, 0.8))
    }

    fun commitPoly() {
        val p = pendingPoly
        pendingPoly = null
        if (p == null || p.size < 6) return
        addRegion(DetectionRegionShape.poly, p)
    }

    fun deleteAt(index: Int) {
        if (index !in regions.indices) return
        setActive(regions.toMutableList().also { it.removeAt(index) })
        when {
            selected == index -> {
                selected = -1
            }
            selected > index -> selected--
        }
    }

    fun deleteSelected() {
        val i = selected
        if (i !in regions.indices) return
        setActive(regions.toMutableList().also { it.removeAt(i) })
        selected = -1
    }

    fun clearAll() {
        setActive(emptyList())
        selected = -1
        pendingPoly = null
    }

    private fun addRegion(shapeValue: String, points: List<Double>) {
        val id = "r${nextId}"
        nextId++
        setActive(
            regions + DetectionRegion(
                id = id,
                shape = shapeValue,
                label = "Region $nextId",
                points = points,
            ),
        )
        select(regions.size - 1)
    }

    private fun hitRegion(nx: Double, ny: Double): Int {
        for (i in regions.indices.reversed()) {
            if (pointInRegion(regions[i], nx, ny)) return i
        }
        return -1
    }

    private fun translate(pts: List<Double>, dx: Double, dy: Double): List<Double> {
        val out = ArrayList<Double>(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            out.add((pts[i] + dx).coerceIn(0.0, 1.0))
            out.add((pts[i + 1] + dy).coerceIn(0.0, 1.0))
            i += 2
        }
        return out
    }

    private fun nearCorner(r: DetectionRegion, x: Double, y: Double): Boolean {
        val tol = 0.06
        val x0 = r.points[0]
        val y0 = r.points[1]
        val x1 = r.points[2]
        val y1 = r.points[3]
        return ((x - x0 <= tol && x0 - x <= tol) && (y - y0 <= tol && y0 - y <= tol)) ||
            ((x - x1 <= tol && x1 - x <= tol) && (y - y1 <= tol && y1 - y <= tol))
    }
}
