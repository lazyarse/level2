package io.securitycam.level1.ui.monitor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Pinch-to-zoom + double-tap reset over the preview. `onApplyFactor` receives
 * the multiplicative gesture factor and is expected to apply it against the
 * current zoom ratio (the caller owns the clamp via the service); `onReset`
 * returns to 1×.
 *
 * Two separate `pointerInput`s: `detectTransformGestures` handles the pinch
 * (multi-pointer / movement), `detectTapGestures` handles the double-tap; the
 * tap detector cancels as soon as a second pointer or drag appears, so the
 * pair composes without fighting.
 */
fun Modifier.zoomGestures(
    onApplyFactor: (Float) -> Unit,
    onReset: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
            if (zoom != 1f) onApplyFactor(zoom)
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onReset() })
    }

/** Small zoom % badge, shown top-left over the preview while zoomed. */
@Composable
fun ZoomBadge(zoomRatio: Float, modifier: Modifier = Modifier) {
    if (zoomRatio <= 1.01f) return
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minWidth = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${(zoomRatio * 100).roundToInt()}%",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}