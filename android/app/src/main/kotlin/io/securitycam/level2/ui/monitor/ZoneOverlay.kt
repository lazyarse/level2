package io.securitycam.level2.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.DetectionZoneShape
import kotlin.math.min

/**
 * Maps normalized analysis-frame points through the display rotation into
 * view space.
 *
 * @param fillCrop true matches `PreviewView.ScaleType.FILL_CENTER` (the
 *   monitor screen): the frame is center-cropped to fill the view, so zones
 *   use the same crop math and align with the preview. False letterboxes
 *   (fit) for the zone editor, where uncropped geometry must map 1:1.
 */
object ZoneDisplayMapper {

    /**
     * @param nx/ny normalized analysis-frame coordinate (0..1)
     * @param rotationDegrees display rotation: 0/90/180/270
     * @param frameAspect analysis-frame width/height (default 320x240 = 4/3)
     */
    fun mapPoint(
        nx: Float,
        ny: Float,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float,
        frameAspect: Float = 4f / 3f,
        fillCrop: Boolean = false,
    ): Offset {
        val (dx, dy) = when (rotationDegrees) {
            90 -> 1f - ny to nx
            270 -> ny to 1f - nx
            180 -> 1f - nx to 1f - ny
            else -> nx to ny
        }
        val rotAspect =
            if (rotationDegrees == 90 || rotationDegrees == 270) 1f / frameAspect
            else frameAspect
        if (!fillCrop) {
            val scale = min(viewWidth / rotAspect, viewHeight)
            val dispW = rotAspect * scale
            val dispH = scale
            val ox = (viewWidth - dispW) / 2f
            val oy = (viewHeight - dispH) / 2f
            return Offset(ox + dx * dispW, oy + dy * dispH)
        }
        // Center-crop: scale so the frame covers the view, then center.
        val scale = maxOf(viewWidth / rotAspect, viewHeight)
        val dispW = rotAspect * scale
        val dispH = scale
        val ox = (viewWidth - dispW) / 2f
        val oy = (viewHeight - dispH) / 2f
        return Offset(ox + dx * dispW, oy + dy * dispH)
    }

    /**
     * Exact inverse of [mapPoint] (same geometry, reversed): view-space point
     * back to normalized analysis-frame coordinates. The zone editor converts
     * touches through this so stored zones match what [mapPoint] draws.
     * Clamped to [0..1] like the editor's legacy converter.
     */
    fun unmapPoint(
        vx: Float,
        vy: Float,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float,
        frameAspect: Float = 4f / 3f,
        fillCrop: Boolean = false,
    ): Offset {
        val rotAspect =
            if (rotationDegrees == 90 || rotationDegrees == 270) 1f / frameAspect
            else frameAspect
        val scale = if (!fillCrop) {
            min(viewWidth / rotAspect, viewHeight)
        } else {
            maxOf(viewWidth / rotAspect, viewHeight)
        }
        val dispW = rotAspect * scale
        val dispH = scale
        val ox = (viewWidth - dispW) / 2f
        val oy = (viewHeight - dispH) / 2f
        val dx = (vx - ox) / dispW
        val dy = (vy - oy) / dispH
        val (nx, ny) = when (rotationDegrees) {
            90 -> dy to 1f - dx
            270 -> 1f - dy to dx
            180 -> 1f - dx to 1f - dy
            else -> dx to dy
        }
        return Offset(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /** Builds the display-space Path for a zone (rects → closed box). */
    fun zonePath(
        zone: DetectionZone,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float,
        frameAspect: Float = 4f / 3f,
        fillCrop: Boolean = false,
    ): Path {
        val path = Path()
        if (zone.shape == DetectionZoneShape.rect && zone.points.size >= 4) {
            val p0 = mapPoint(zone.points[0].toFloat(), zone.points[1].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect, fillCrop)
            val p1 = mapPoint(zone.points[2].toFloat(), zone.points[3].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect, fillCrop)
            // Normalize: a reversed rect (x1 < x0) draws nothing — order it.
            path.addRect(
                Rect(
                    min(p0.x, p1.x),
                    min(p0.y, p1.y),
                    kotlin.math.max(p0.x, p1.x),
                    kotlin.math.max(p0.y, p1.y),
                ),
            )
            return path
        }
        var first = true
        var i = 0
        while (i + 1 < zone.points.size) {
            val p = mapPoint(zone.points[i].toFloat(), zone.points[i + 1].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect, fillCrop)
            if (first) {
                path.moveTo(p.x, p.y)
                first = false
            } else {
                path.lineTo(p.x, p.y)
            }
            i += 2
        }
        path.close()
        return path
    }
}

private val ZonePalette = listOf(
    Color(0xCC8AB4F8),
    Color(0xCC81C995),
    Color(0xCCFDD663),
    Color(0xCCF28B82),
    Color(0xCCD7AEFB),
)

/** Distinct overlay color for exclusion (privacy) zones. */
private val ExclusionOverlay = Color(0xCCEA4335)

/** Draws [zones] over the preview in display space, plus [exclusionZones] in red. */
@Composable
fun ZoneOverlay(
    zones: List<DetectionZone>,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    show: Boolean = true,
    exclusionZones: List<DetectionZone> = emptyList(),
    /** Must match the preview's scale type: true for FILL_CENTER (monitor). */
    fillCrop: Boolean = true,
) {
    if (!show) return
    Canvas(modifier = modifier) {
        if (zones.isEmpty() && exclusionZones.isEmpty()) {
            drawRect(
                color = Color(0x80FFFFFF),
                style = Stroke(width = 2f),
            )
            return@Canvas
        }
        val size = this.size
        zones.forEachIndexed { index, zone ->
            val path = ZoneDisplayMapper.zonePath(
                zone, rotationDegrees, size.width, size.height, fillCrop = fillCrop,
            )
            drawPath(
                path = path,
                color = ZonePalette[index % ZonePalette.size],
                style = Stroke(width = 1.5f),
            )
        }
        exclusionZones.forEach { zone ->
            val path = ZoneDisplayMapper.zonePath(
                zone, rotationDegrees, size.width, size.height, fillCrop = fillCrop,
            )
            drawPath(
                path = path,
                color = ExclusionOverlay,
                style = Stroke(width = 1.5f),
            )
        }
    }
}