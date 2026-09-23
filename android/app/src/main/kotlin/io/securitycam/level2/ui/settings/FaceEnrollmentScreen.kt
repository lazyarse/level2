package io.securitycam.level2.ui.settings

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.ui.monitor.PreviewSurface
import io.securitycam.level2.ui.theme.AppButtonShape
import kotlin.math.max

/**
 * Full-screen capture page for face enrollment: LIVE phase shows the camera
 * viewfinder with a static oval alignment mask and a shutter; REVIEW phase
 * swaps in the captured frame (face box outlined) with Use/Retake. Hosted by
 * [io.securitycam.level2.SecurityCamApp] while enrollment is in flight; the
 * Cancel action (and system back) abandons the attempt from either phase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceEnrollmentScreen(
    label: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onFlipCamera: () -> Unit = {},
    canFlipCamera: Boolean = true,
    capturedFrame: Pair<ColorBitmap, FaceDetection>? = null,
    error: String? = null,
    shutterArmed: Boolean = false,
    onShutter: () -> Unit = {},
    onUsePhoto: () -> Unit = {},
    onRetake: () -> Unit = {},
    /** True when the capture came from the front camera (mirrored selfie). */
    mirrorFront: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Enrol face") },
                actions = {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("cancelEnrollmentButton"),
                        shape = AppButtonShape,
                    ) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // The 3:4 preview + controls exceed small viewport heights;
                // scroll so the shutter/review buttons stay reachable.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (capturedFrame != null) {
                ReviewContent(capturedFrame, mirrorFront)
                Spacer(Modifier.height(24.dp))
                Text(
                    "Check the photo",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use it to finish, or retake for another attempt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onUsePhoto,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("usePhotoButton"),
                        shape = AppButtonShape,
                    ) { Text("Use photo") }
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("retakePhotoButton"),
                        shape = AppButtonShape,
                    ) { Text("Retake") }
                }
            } else {
                LiveContent(
                    onFlipCamera = onFlipCamera,
                    canFlipCamera = canFlipCamera,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Enrolling $label…",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (error != null) {
                    Text(
                        error,
                        modifier = Modifier.testTag("enrollmentError"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Position your face in the oval.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onShutter,
                    enabled = shutterArmed,
                    modifier = Modifier.testTag("enrollmentShutterButton"),
                    shape = AppButtonShape,
                ) { Text("Take photo") }
            }
        }
    }
}

/** LIVE phase: preview + static oval mask + flip control. */
@Composable
private fun LiveContent(
    onFlipCamera: () -> Unit,
    canFlipCamera: Boolean,
) {
    Box {
        PreviewSurface(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .testTag("enrollmentPreview"),
        )
        // Dim-outside-oval guide: Canvas sibling after the SurfaceView so it
        // composites on top (same pattern as the monitor zone overlay).
        Canvas(
            Modifier
                .matchParentSize()
                .testTag("enrollmentOvalGuide"),
        ) {
            val ovalW = size.width * 0.6f
            val ovalH = size.height * 0.5f
            val left = (size.width - ovalW) / 2f
            val top = (size.height - ovalH) / 2f
            val path = Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addOval(
                    androidx.compose.ui.geometry.Rect(left, top, left + ovalW, top + ovalH),
                )
                fillType = PathFillType.EvenOdd
            }
            clipPath(path) {
                // Even-odd path = rect ∖ oval, so the fill only dims outside
                // the oval; the interior stays clear.
                drawRect(Color.Black.copy(alpha = 0.45f))
            }
            drawPath(
                path = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(left, top, left + ovalW, top + ovalH),
                    )
                },
                color = Color.White.copy(alpha = 0.85f),
                style = Stroke(width = max(2f, size.minDimension * 0.008f)),
            )
        }
        // Front/back flip for the capture session only; disabled when
        // another session (monitoring) owns the camera.
        IconButton(
            onClick = onFlipCamera,
            enabled = canFlipCamera,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .testTag("flipEnrollmentCameraButton"),
        ) {
            Icon(
                Icons.Filled.Cameraswitch,
                contentDescription = "Flip camera",
                tint = if (canFlipCamera) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/** REVIEW phase: static captured frame with the face box outlined. */
@Composable
private fun ReviewContent(
    captured: Pair<ColorBitmap, FaceDetection>,
    mirrorFront: Boolean,
) {
    val (frame, box) = captured
    val bitmap = remember(frame, box, mirrorFront) {
        frame.toReviewBitmap(box, mirrorFront).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = "Captured face photo",
        modifier = Modifier
            .fillMaxWidth()
            // Match the captured frame: a hardcoded portrait ratio
            // letterboxes landscape analysis frames with black bars.
            .aspectRatio(
                bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f),
            )
            .clip(RoundedCornerShape(16.dp))
            .testTag("enrollmentReview"),
    )
}

/**
 * BGR frame → ARGB bitmap with the detected face box stroked on top (baked
 * in so the review image needs no letterbox-aware overlay math). Front
 * captures mirror pixels and box to match the selfie preview.
 */
private fun ColorBitmap.toReviewBitmap(det: FaceDetection, mirror: Boolean = false): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val sx = if (mirror) width - 1 - x else x
            val idx = (y * width + sx) * 3
            val b = bgr[idx].toInt() and 0xFF
            val g = bgr[idx + 1].toInt() and 0xFF
            val r = bgr[idx + 2].toInt() and 0xFF
            pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(2f, minOf(width, height) / 60f)
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }
    val (bx1, bx2) = if (mirror) {
        (1.0 - det.x2) to (1.0 - det.x1)
    } else {
        det.x1 to det.x2
    }
    android.graphics.Canvas(bmp).drawRect(
        (bx1 * width).toFloat(),
        (det.y1 * height).toFloat(),
        (bx2 * width).toFloat(),
        (det.y2 * height).toFloat(),
        paint,
    )
    return bmp
}
