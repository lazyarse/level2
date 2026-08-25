package io.securitycam.level2.ui.events

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.securitycam.level2.core.DetectorType
import io.securitycam.level2.core.Snapshot

/** Icon for a trigger type, shared by the events/history surfaces and monitor status. */
fun eventIconFor(type: String): ImageVector =
    DetectorType.fromKey(type)?.icon ?: Icons.Filled.NotificationImportant

/**
 * Decodes JPEG bytes applying EXIF orientation — BitmapFactory ignores it,
 * which left pre-2026-08-23 snapshots (authored with a stale target rotation)
 * rendering 90° off.
 *
 * When [maxDim] is set the bitmap is downsampled (inSampleSize + exact scale)
 * to fit within that dimension — list thumbnails at a fraction of full-res
 * memory, letting the cache hold hundreds of entries instead of ~8.
 */
fun decodeUpright(bytes: ByteArray, maxDim: Int? = null): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val opts = BitmapFactory.Options()
    if (maxDim != null && maxDim > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) {
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxDim || h / 2 >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        opts.inSampleSize = sample
    }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

    val orientation = try {
        androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
            )
    } catch (_: Exception) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }
    val m = android.graphics.Matrix()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            m.postScale(-1f, 1f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f); m.postScale(-1f, 1f)
        }
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f); m.postScale(-1f, 1f)
        }
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            m.postRotate(180f); m.postScale(-1f, 1f)
        }
    }
    val rotated = if (m.isIdentity) {
        bmp
    } else {
        android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // Exact fit for non-power-of-two overshoot after sampling.
    if (maxDim != null && maxDim > 0 &&
        (rotated.width > maxDim || rotated.height > maxDim)
    ) {
        val scale = minOf(
            maxDim.toFloat() / rotated.width,
            maxDim.toFloat() / rotated.height,
        )
        val tw = (rotated.width * scale).toInt().coerceAtLeast(1)
        val th = (rotated.height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(rotated, tw, th, true)
    }
    return rotated
}

/**
 * Snapshot thumbnail with a fallback icon while loading / when missing; tap
 * opens the zoomable full view. Shared by Events rows and the History gallery.
 */
@Composable
internal fun SnapshotThumb(
    name: String,
    fallbackIcon: ImageVector,
    title: String,
    loader: suspend (String) -> Snapshot?,
    tag: String,
    size: Dp = 48.dp,
) {
    // Synchronous first-frame render for previously-seen thumbs; only true
    // misses fall back to the icon and fill asynchronously.
    var thumb by remember(name) {
        mutableStateOf(ThumbCache.peek("snap:$name"))
    }
    if (thumb == null) {
        androidx.compose.runtime.LaunchedEffect(name) {
            thumb = ThumbCache.getOrLoad(
                "snap:$name",
                ThumbCache.THUMB_MAX_DIM,
            ) { loader(name)?.bytes }
        }
    }
    var showFull by remember { mutableStateOf(false) }
    var full by remember(name) { mutableStateOf<android.graphics.Bitmap?>(null) }
    if (showFull && full == null) {
        androidx.compose.runtime.LaunchedEffect(name) {
            full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loader(name)?.bytes?.let { decodeUpright(it) }
            }
        }
    }

    val decoded = thumb
    if (decoded == null) {
        Icon(
            fallbackIcon,
            contentDescription = null,
            modifier = Modifier.size(size).testTag("${tag}Fallback"),
        )
    } else {
        Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .clickable { showFull = true }
                .testTag(tag),
        )
    }
    if (showFull) {
        ZoomableSnapshotDialog(
            bitmap = full,
            loading = full == null && showFull,
            title = title,
            onClose = { showFull = false },
        )
    }
}

/** Zoomable (pinch 1x–8x + pan) full-size snapshot dialog. */
@Composable
internal fun ZoomableSnapshotDialog(
    bitmap: android.graphics.Bitmap?,
    loading: Boolean = false,
    title: String,
    onClose: () -> Unit,
    closeTag: String = "eventClose",
) {
    Dialog(onDismissRequest = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, modifier = Modifier.padding(8.dp))
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            },
                    )
                } else if (loading) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag(closeTag),
            ) { Text("Close") }
        }
    }
}
