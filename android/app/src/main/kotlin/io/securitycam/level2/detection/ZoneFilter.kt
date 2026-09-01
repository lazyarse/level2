package io.securitycam.level2.detection

/**
 * Zone geometry helpers (port of `lib/detection/zones/zone_filter.dart`).
 * Coordinates are normalized 0..1 on the analysis frame.
 */
object ZoneFilter {

    /** Whether (x, y) is inside [zone]. */
    fun pointInZone(zone: DetectionZone, x: Double, y: Double): Boolean {
        if (zone.shape == DetectionZoneShape.rect) {
            val x0 = zone.points[0]
            val y0 = zone.points[1]
            val x1 = zone.points[2]
            val y1 = zone.points[3]
            return x >= x0 && x <= x1 && y >= y0 && y <= y1
        }
        return pointInPolygon(x, y, zone.points)
    }

    /** Ray-casting point-in-polygon over a flattened [x0,y0,x1,y1,...] list. */
    fun pointInPolygon(x: Double, y: Double, pts: List<Double>): Boolean {
        var inside = false
        var j = pts.size - 2
        var i = 0
        while (i < pts.size) {
            val xi = pts[i]
            val yi = pts[i + 1]
            val xj = pts[j]
            val yj = pts[j + 1]
            val intersects = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    /** True when the box (x, y, w, h) overlaps ANY zone. Empty = whole frame. */
    fun rectOverlapsAny(
        zones: List<DetectionZone>,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Boolean {
        if (zones.isEmpty()) return true
        for (zone in zones) {
            if (boxOverlapsZone(zone, x, y, w, h)) return true
        }
        return false
    }

    /**
     * Exclusion-side overlap test: unlike [rectOverlapsAny], an empty list
     * excludes nothing (a box never hits an absent zone).
     */
    fun boxHitsAnyExclusion(
        exclusions: List<DetectionZone>,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Boolean = exclusions.isNotEmpty() && rectOverlapsAny(exclusions, x, y, w, h)

    private fun boxOverlapsZone(
        zone: DetectionZone,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Boolean {
        if (zone.shape == DetectionZoneShape.rect) {
            val x0 = zone.points[0]
            val y0 = zone.points[1]
            val x1 = zone.points[2]
            val y1 = zone.points[3]
            // Inclusive edges: a box merely touching the border counts as overlap.
            return x <= x1 && x + w >= x0 && y <= y1 && y + h >= y0
        }
        val corners = listOf(
            x to y,
            x + w to y,
            x to y + h,
            x + w to y + h,
        )
        for ((cx, cy) in corners) {
            if (pointInPolygon(cx, cy, zone.points)) return true
        }
        val boxX0 = x
        val boxY0 = y
        val boxX1 = x + w
        val boxY1 = y + h
        var i = 0
        while (i < zone.points.size) {
            val vx = zone.points[i]
            val vy = zone.points[i + 1]
            if (vx >= boxX0 && vx <= boxX1 && vy >= boxY0 && vy <= boxY1) return true
            i += 2
        }
        val boxEdges = listOf(
            (boxX0 to boxY0) to (boxX1 to boxY0),
            (boxX1 to boxY0) to (boxX1 to boxY1),
            (boxX1 to boxY1) to (boxX0 to boxY1),
            (boxX0 to boxY1) to (boxX0 to boxY0),
        )
        var j = zone.points.size - 2
        i = 0
        while (i < zone.points.size) {
            val xi = zone.points[i]
            val yi = zone.points[i + 1]
            val xj = zone.points[j]
            val yj = zone.points[j + 1]
            for (edge in boxEdges) {
                if (segmentsIntersect(
                        edge.first.first, edge.first.second,
                        edge.second.first, edge.second.second,
                        xi, yi, xj, yj,
                    )
                ) return true
            }
            j = i
            i += 2
        }
        return false
    }

    private fun orient(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Double = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSegment(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean = bx <= kotlin.math.max(ax, cx) && bx >= kotlin.math.min(ax, cx) &&
        by <= kotlin.math.max(ay, cy) && by >= kotlin.math.min(ay, cy)

    private fun segmentsIntersect(
        p1x: Double, p1y: Double, p2x: Double, p2y: Double,
        p3x: Double, p3y: Double, p4x: Double, p4y: Double,
    ): Boolean {
        val o1 = orient(p1x, p1y, p2x, p2y, p3x, p3y)
        val o2 = orient(p1x, p1y, p2x, p2y, p4x, p4y)
        val o3 = orient(p3x, p3y, p4x, p4y, p1x, p1y)
        val o4 = orient(p3x, p3y, p4x, p4y, p2x, p2y)
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) &&
            ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))
        ) {
            return true
        }
        if (o1 == 0.0 && onSegment(p1x, p1y, p3x, p3y, p2x, p2y)) return true
        if (o2 == 0.0 && onSegment(p1x, p1y, p4x, p4y, p2x, p2y)) return true
        if (o3 == 0.0 && onSegment(p3x, p3y, p1x, p1y, p4x, p4y)) return true
        if (o4 == 0.0 && onSegment(p3x, p3y, p2x, p2y, p4x, p4y)) return true
        return false
    }

    /**
     * Builds a byte mask (1 = inside ANY zone) and the count of 1-bits, using
     * each pixel's center. Empty zones → all ones.
     */
    fun pixelMask(
        zones: List<DetectionZone>,
        width: Int,
        height: Int,
    ): Pair<ByteArray, Int> {
        val mask = ByteArray(width * height)
        if (zones.isEmpty()) {
            mask.fill(1)
            return mask to mask.size
        }
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = (x + 0.5) / width
                val ny = (y + 0.5) / height
                var inside = false
                for (zone in zones) {
                    if (pointInZone(zone, nx, ny)) {
                        inside = true
                        break
                    }
                }
                if (inside) {
                    mask[y * width + x] = 1
                    count++
                }
            }
        }
        return mask to count
    }

    /**
     * Builds a byte mask honoring exclusion zones: starts from the inclusion mask
     * (all ones when [inclusions] is empty), then clears every pixel whose center
     * lies inside any zone of [exclusions]. Exclusion wins over inclusion.
     */
    fun pixelMaskExcluding(
        inclusions: List<DetectionZone>,
        exclusions: List<DetectionZone>,
        width: Int,
        height: Int,
    ): Pair<ByteArray, Int> {
        val (mask, _) = pixelMask(inclusions, width, height)
        if (exclusions.isEmpty()) return mask to mask.count { it == 1.toByte() }
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (mask[index] != 1.toByte()) continue
                val nx = (x + 0.5) / width
                val ny = (y + 0.5) / height
                var excluded = false
                for (zone in exclusions) {
                    if (pointInZone(zone, nx, ny)) {
                        excluded = true
                        break
                    }
                }
                if (!excluded) {
                    count++
                } else {
                    mask[index] = 0
                }
            }
        }
        return mask to count
    }
}