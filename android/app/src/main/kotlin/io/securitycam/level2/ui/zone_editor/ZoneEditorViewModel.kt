package io.securitycam.level2.ui.zones

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.DetectionZoneShape
import io.securitycam.level2.detection.ZoneFilter.pointInZone

/** Which list the editor tools currently operate on. */
enum class ZoneEditorMode { inclusion, exclusion, tripwire }

/**
 * What a drag grabbed, hit-tested by the composable in screen pixels (it
 * owns the canvas size and letterbox). Rect corners are 0=TL, 1=TR, 2=BR,
 * 3=BL; poly vertices are positional indices into the flattened points.
 */
sealed interface ZoneGrab {
    data class RectCorner(val zone: Int, val corner: Int) : ZoneGrab
    data class PolyVertex(val zone: Int, val vertex: Int) : ZoneGrab
    data class Body(val zone: Int) : ZoneGrab
    data object Empty : ZoneGrab
}

/** Smallest allowed rect edge, matching the new-rect draw threshold. */
private const val MIN_ZONE = 0.02

/**
 * Interaction logic for the zone editor: tap-to-select / poly-vertex placement,
 * drag-to-draw new rects, corner-resize and move of existing rects, label
 * editing, delete, clear — extended per the privacy-zones design to edit BOTH
 * the inclusion and exclusion lists through an active-mode toggle. All
 * coordinates are normalized (0..1) analysis-frame space; the composable only
 * converts pointer offsets.
 */
class ZoneEditorViewModel(
    initialZones: List<DetectionZone>,
    initialExclusions: List<DetectionZone> = emptyList(),
    initialTripwireZones: List<DetectionZone> = emptyList(),
) {

    var inclusionZones by mutableStateOf(initialZones)
        private set
    var exclusionZones by mutableStateOf(initialExclusions)
        private set
    var tripwireZones by mutableStateOf(initialTripwireZones)
        private set
    var tripwireDirection by mutableStateOf("either")
        private set
    var mode by mutableStateOf(ZoneEditorMode.inclusion)
        private set

    /** The list the current [mode] edits; every tool below operates on it. */
    val zones: List<DetectionZone>
        get() = when (mode) {
            ZoneEditorMode.inclusion -> inclusionZones
            ZoneEditorMode.exclusion -> exclusionZones
            ZoneEditorMode.tripwire -> tripwireZones
        }

    var selected by mutableIntStateOf(-1)
        private set
    var shape by mutableStateOf(DetectionZoneShape.rect)
        private set
    var pendingPoly by mutableStateOf<List<Double>?>(null)
        private set
    var dragRect by mutableStateOf<List<Double>?>(null)
        private set

    private var nextId = 1
    private var dragStart: Pair<Double, Double>? = null
    private var dragLast: Pair<Double, Double>? = null
    private var dragHandle: ZoneGrab? = null
    private var dragMoving = false

    fun chooseMode(value: ZoneEditorMode) {
        if (mode == value) return
        mode = value
        selected = -1
        pendingPoly = null
        dragRect = null
        dragStart = null
        dragLast = null
        dragHandle = null
        dragMoving = false
    }

    fun chooseTripwireDirection(direction: String) {
        tripwireDirection = direction
    }

    private fun setActive(value: List<DetectionZone>) {
        when (mode) {
            ZoneEditorMode.inclusion -> inclusionZones = value
            ZoneEditorMode.exclusion -> exclusionZones = value
            ZoneEditorMode.tripwire -> tripwireZones = value
        }
    }

    fun select(index: Int) {
        selected = index
    }

    fun chooseShape(value: String) {
        shape = value
        if (value == DetectionZoneShape.rect) pendingPoly = null
    }

    /** Label edit; empty input keeps the previous label (Dart parity). */
    fun renameSelected(label: String) {
        val i = selected
        if (i !in zones.indices) return
        val r = zones[i]
        setActive(zones.toMutableList().also {
            it[i] = r.copy(label = label.trim().ifEmpty { r.label })
        })
    }

    fun onTap(nx: Double, ny: Double) {
        if (shape == DetectionZoneShape.poly) {
            // First tap in poly mode STARTS the pending polygon.
            val p = pendingPoly ?: emptyList()
            pendingPoly = p + listOf(nx, ny)
            return
        }
        select(hitZone(nx, ny))
    }

    fun onPanStart(nx: Double, ny: Double, grab: ZoneGrab) {
        when (grab) {
            is ZoneGrab.RectCorner -> {
                val r = zones.getOrNull(grab.zone)
                if (r == null || r.shape != DetectionZoneShape.rect || r.points.size < 4) {
                    startMove(grab.zone, nx, ny)
                } else {
                    select(grab.zone)
                    dragHandle = grab
                    dragLast = nx to ny
                }
            }
            is ZoneGrab.PolyVertex -> {
                val r = zones.getOrNull(grab.zone)
                if (r == null || r.shape != DetectionZoneShape.poly ||
                    grab.vertex * 2 + 1 >= r.points.size
                ) {
                    startMove(grab.zone, nx, ny)
                } else {
                    select(grab.zone)
                    dragHandle = grab
                    dragLast = nx to ny
                }
            }
            is ZoneGrab.Body -> startMove(grab.zone, nx, ny)
            ZoneGrab.Empty -> {
                if (shape == DetectionZoneShape.rect) {
                    // Start a new rectangle at the drag origin.
                    selected = -1
                    pendingPoly = null
                    dragStart = nx to ny
                    dragLast = nx to ny
                    dragRect = listOf(nx, ny, nx, ny)
                }
            }
        }
    }

    /**
     * Programmatic path (tests, non-canvas callers): a hit grabs the body,
     * a miss grabs empty space. Handle grabs always go through the
     * three-arg overload with a pixel-tested [ZoneGrab].
     */
    fun onPanStart(nx: Double, ny: Double) {
        val hit = hitZone(nx, ny)
        onPanStart(nx, ny, if (hit >= 0) ZoneGrab.Body(hit) else ZoneGrab.Empty)
    }

    private fun startMove(zone: Int, nx: Double, ny: Double) {
        if (zone !in zones.indices) return
        select(zone)
        dragMoving = true
        // Anchor the first move delta at the grab point.
        dragLast = nx to ny
    }

    fun onPanUpdate(nx: Double, ny: Double) {
        val n = nx to ny
        when (val handle = dragHandle) {
            is ZoneGrab.RectCorner -> return resizeRect(handle, nx, ny)
            is ZoneGrab.PolyVertex -> return moveVertex(handle, nx, ny)
            else -> Unit
        }
        when {
            dragMoving -> {
                val i = selected
                val last = dragLast
                if (i in zones.indices && last != null) {
                    val r = zones[i]
                    setActive(zones.toMutableList().also {
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

    /** Dragged rect corner follows the pointer; the opposite corner anchors. */
    private fun resizeRect(grab: ZoneGrab.RectCorner, nx: Double, ny: Double) {
        if (grab.zone !in zones.indices) return
        val r = zones[grab.zone]
        if (r.shape != DetectionZoneShape.rect || r.points.size < 4) return
        val p = r.points
        val (ax, ay) = cornerCoord(p, (grab.corner + 2) % 4)
        val gx = resizeAxis(nx, ax)
        val gy = resizeAxis(ny, ay)
        val np = p.toMutableList()
        when (grab.corner) {
            0 -> { np[0] = gx; np[1] = gy }
            1 -> { np[2] = gx; np[1] = gy }
            2 -> { np[2] = gx; np[3] = gy }
            else -> { np[0] = gx; np[3] = gy }
        }
        setActive(zones.toMutableList().also { it[grab.zone] = r.copy(points = np) })
    }

    /** Dragged poly vertex follows the pointer, clamped to the unit square. */
    private fun moveVertex(grab: ZoneGrab.PolyVertex, nx: Double, ny: Double) {
        if (grab.zone !in zones.indices) return
        val r = zones[grab.zone]
        if (r.shape != DetectionZoneShape.poly || grab.vertex * 2 + 1 >= r.points.size) return
        val np = r.points.toMutableList()
        np[grab.vertex * 2] = nx.coerceIn(0.0, 1.0)
        np[grab.vertex * 2 + 1] = ny.coerceIn(0.0, 1.0)
        setActive(zones.toMutableList().also { it[grab.zone] = r.copy(points = np) })
    }

    private fun cornerCoord(pts: List<Double>, corner: Int): Pair<Double, Double> = when (corner) {
        0 -> pts[0] to pts[1]
        1 -> pts[2] to pts[1]
        2 -> pts[2] to pts[3]
        else -> pts[0] to pts[3]
    }

    /** Clamps to the unit square and keeps a MIN_ZONE edge against [anchor]. */
    private fun resizeAxis(pos: Double, anchor: Double): Double {
        val c = pos.coerceIn(0.0, 1.0)
        return if (c < anchor) {
            minOf(c, anchor - MIN_ZONE).coerceIn(0.0, 1.0)
        } else {
            maxOf(c, anchor + MIN_ZONE).coerceIn(0.0, 1.0)
        }
    }

    fun onPanEnd() {
        val rect = dragRect
        val handle = dragHandle
        dragStart = null
        dragLast = null
        dragHandle = null
        dragMoving = false
        dragRect = null
        // Heal ordering (e.g. legacy inverted rects): the detector hit-test
        // and the display both assume x0<=x1 and y0<=y1.
        (handle as? ZoneGrab.RectCorner)?.let { grab ->
            if (grab.zone in zones.indices) {
                val r = zones[grab.zone]
                if (r.shape == DetectionZoneShape.rect && r.points.size >= 4) {
                    val p = r.points
                    setActive(zones.toMutableList().also {
                        it[grab.zone] = r.copy(
                            points = listOf(
                                minOf(p[0], p[2]),
                                minOf(p[1], p[3]),
                                maxOf(p[0], p[2]),
                                maxOf(p[1], p[3]),
                            ),
                        )
                    })
                }
            }
        }
        // Commit the newly drawn rectangle (skipped for tiny drags).
        if (rect != null &&
            kotlin.math.abs(rect[2] - rect[0]) >= MIN_ZONE &&
            kotlin.math.abs(rect[3] - rect[1]) >= MIN_ZONE
        ) {
            addZone(DetectionZoneShape.rect, rect)
        }
    }

    fun addZone() {
        pendingPoly = null
        addZone(DetectionZoneShape.rect, listOf(0.2, 0.2, 0.8, 0.8))
    }

    fun commitPoly() {
        val p = pendingPoly
        pendingPoly = null
        if (p == null || p.size < 6) return
        addZone(DetectionZoneShape.poly, p)
    }

    fun deleteAt(index: Int) {
        if (index !in zones.indices) return
        setActive(zones.toMutableList().also { it.removeAt(index) })
        when {
            selected == index -> {
                selected = -1
            }
            selected > index -> selected--
        }
    }

    fun deleteSelected() {
        val i = selected
        if (i !in zones.indices) return
        setActive(zones.toMutableList().also { it.removeAt(i) })
        selected = -1
    }

    fun clearAll() {
        setActive(emptyList())
        selected = -1
        pendingPoly = null
    }

    private fun addZone(shapeValue: String, points: List<Double>) {
        val id = "r${nextId}"
        nextId++
        val direction = if (mode == ZoneEditorMode.tripwire) tripwireDirection else "either"
        setActive(
            zones + DetectionZone(
                id = id,
                shape = shapeValue,
                label = "Zone $nextId",
                points = points,
                direction = direction,
            ),
        )
        select(zones.size - 1)
    }

    private fun hitZone(nx: Double, ny: Double): Int {
        for (i in zones.indices.reversed()) {
            if (pointInZone(zones[i], nx, ny)) return i
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
}
