package io.securitycam.level1.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectionRegionShape
import kotlin.math.min

/**
 * Maps normalized analysis-frame points through the display rotation into
 * view space, letterboxing the rotated frame aspect into the view (the same
 * fit `PreviewView.ScaleType.FILL_CENTER` applies to the camera preview).
 */
object RegionDisplayMapper {

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
        val scale = min(viewWidth / rotAspect, viewHeight)
        val dispW = rotAspect * scale
        val dispH = scale
        val ox = (viewWidth - dispW) / 2f
        val oy = (viewHeight - dispH) / 2f
        return Offset(ox + dx * dispW, oy + dy * dispH)
    }

    /** Builds the display-space Path for a region (rects → closed box). */
    fun regionPath(
        region: DetectionRegion,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float,
        frameAspect: Float = 4f / 3f,
    ): Path {
        val path = Path()
        if (region.shape == DetectionRegionShape.rect && region.points.size >= 4) {
            val p0 = mapPoint(region.points[0].toFloat(), region.points[1].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect)
            val p1 = mapPoint(region.points[2].toFloat(), region.points[3].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect)
            path.addRect(Rect(p0, p1))
            return path
        }
        var first = true
        var i = 0
        while (i + 1 < region.points.size) {
            val p = mapPoint(region.points[i].toFloat(), region.points[i + 1].toFloat(), rotationDegrees, viewWidth, viewHeight, frameAspect)
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

private val RegionPalette = listOf(
    Color(0xCC8AB4F8),
    Color(0xCC81C995),
    Color(0xCCFDD663),
    Color(0xCCF28B82),
    Color(0xCCD7AEFB),
)

/** Distinct overlay color for exclusion (privacy) zones. */
private val ExclusionOverlay = Color(0xCCEA4335)

/** Draws [regions] over the preview in display space, plus [exclusionRegions] in red. */
@Composable
fun RegionOverlay(
    regions: List<DetectionRegion>,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    show: Boolean = true,
    exclusionRegions: List<DetectionRegion> = emptyList(),
) {
    if (!show || (regions.isEmpty() && exclusionRegions.isEmpty())) return
    Canvas(modifier = modifier) {
        val size = this.size
        regions.forEachIndexed { index, region ->
            val path = RegionDisplayMapper.regionPath(
                region, rotationDegrees, size.width, size.height,
            )
            drawPath(
                path = path,
                color = RegionPalette[index % RegionPalette.size],
                style = Stroke(width = 1.5f),
            )
        }
        exclusionRegions.forEach { region ->
            val path = RegionDisplayMapper.regionPath(
                region, rotationDegrees, size.width, size.height,
            )
            drawPath(
                path = path,
                color = ExclusionOverlay,
                style = Stroke(width = 1.5f),
            )
        }
    }
}