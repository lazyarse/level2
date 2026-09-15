package io.securitycam.level2.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import io.securitycam.level2.core.GifPreview
import io.securitycam.level2.core.Snapshot
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout


/**
 * Extracts keyframes from a stored [clipName] clip and encodes them as a short,
 * low-frame-rate MP4 for notification channels, plus a still JPEG (frame 0)
 * for image-only channels (Pushover).
 *
 * Splits cleanly for tests: frame sampling (Bitmap / MediaMetadataRetriever)
 * and MediaCodec/MediaMuxer are the Android parts — [Mp4Encoder] ARGB→I420
 * conversion is plain JVM.
 */
data class Preview(
    val video: Snapshot,
    val still: Snapshot,
)

class Mp4PreviewGenerator(private val context: Context) {

    /**
     * Samples the first [GifPreview.MAX_DURATION_SECONDS] of the clip at the
     * configured [fps], downscaled to at most [maxWidthPx] wide, and returns a
     * [Preview] holding the MP4 `<clipStem>.mp4` plus a still JPEG
     * `<clipStem>.jpg` (frame 0), or null when the clip can't be read
     * (never throws — the fast text alert already went out).
     */
    suspend fun generate(clipName: String, fps: Int, maxWidthPx: Int): Preview? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                val source = clipSource(clipName)
                when (source) {
                    is ClipSource.Uri -> retriever.setDataSource(context, source.uri)
                    is ClipSource.File -> retriever.setDataSource(source.path)
                    is ClipSource.Missing -> {
                        Log.w("Mp4Preview", "clipSource Missing for $clipName")
                        return@runCatching null
                    }
                }
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1000L
                val srcWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0
                val srcHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0
                if (durationUs <= 0L || srcWidth <= 0 || srcHeight <= 0) {
                    Log.w(
                        "Mp4Preview",
                        "generate: bad header clip=$clipName durationUs=$durationUs (ms=$durationMs) src=${srcWidth}x${srcHeight} fps=$fps maxWidthPx=$maxWidthPx",
                    )
                    return@runCatching null
                }

                // Whole video, x fps decimated to MAX_FRAMES cap and stretched so wall time matches clip.
                val clampedFps = fps.coerceIn(GifPreview.MIN_FPS, GifPreview.MAX_FPS)
                val targetUs = durationUs
                val idealCount = (targetUs * clampedFps / 1_000_000L + 1).toInt()
                val sampleCount = minOf(idealCount, GifPreview.MAX_FRAMES).coerceAtLeast(1)
                val intervalUs = if (sampleCount > 1) targetUs / (sampleCount - 1) else 0L
                val delayCs = if (sampleCount > 1) ((targetUs / 10000L) / sampleCount).toInt().coerceAtLeast(1)
                else (100 / clampedFps).coerceAtLeast(1)

                // Shrink the frame to stay inside the encode pixel budget: user's
                // maxWidthPx stays the ceiling, fps drives sampleCount, and the
                // per-frame pixel budget = totalBudget / sampleCount keeps the LZW
                // encode under withTimeout on the SM_A137F-class device.
                val budgetW = kotlin.math.sqrt(
                    GifPreview.PIXEL_BUDGET.toDouble() / sampleCount * srcWidth / srcHeight,
                ).toInt()
                val rawWidth = minOf(
                    maxWidthPx.coerceAtLeast(128),
                    budgetW.coerceAtLeast(128),
                    srcWidth,
                )
                val rawHeight = maxOf(128, srcHeight * rawWidth / srcWidth)
                // Encoder requires at least 128x128 (VideoCapabilities) and even dims
                val width = maxOf(128, rawWidth and 0x7FFFFFFE)
                val height = maxOf(128, rawHeight and 0x7FFFFFFE)

                val frames = ArrayList<Mp4Encoder.Frame>(sampleCount)
                Log.i(
                    "Mp4Preview",
                    "generate start clip=$clipName durationUs=$durationUs (ideal=$idealCount) src=${srcWidth}x${srcHeight} width=$width height=$height fps=$fps maxWidthPx=$maxWidthPx sampleCount=$sampleCount intervalUs=$intervalUs delayCs=$delayCs",
                )
                var frameWidth = -1
                var frameHeight = -1
                for (i in 0 until sampleCount) {
                    val atUs = minOf(i * intervalUs, durationUs - 1).coerceAtLeast(0L)
                    val bitmap = retriever.getScaledFrameAtTime(
                        atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height,
                    )
                    if (bitmap == null) {
                        Log.w("Mp4Preview", "getScaledFrameAtTime null clip=$clipName atUs=$atUs i=$i/$sampleCount width=$width height=$height")
                        continue
                    }
                    var useBitmap: Bitmap = bitmap
                    var scaled: Bitmap? = null
                    try {
                        val bw = bitmap.width
                        val bh = bitmap.height
                        if (frameWidth == -1) {
                            frameWidth = maxOf(128, bw and 0x7FFFFFFE)
                            frameHeight = maxOf(128, bh and 0x7FFFFFFE)
                        }
                        if (bw != frameWidth || bh != frameHeight) {
                            Log.w(
                                "Mp4Preview",
                                "frame size mismatch clip=$clipName i=$i bitmap=${bw}x$bh expected=${frameWidth}x${frameHeight} scaling",
                            )
                            scaled = Bitmap.createScaledBitmap(bitmap, frameWidth, frameHeight, true)
                            useBitmap = scaled
                        }
                        val pixels = IntArray(frameWidth * frameHeight)
                        useBitmap.getPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
                        frames.add(Mp4Encoder.Frame(delayCs, frameWidth, frameHeight, pixels))
                    } catch (e: Exception) {
                        Log.w("Mp4Preview", "frame $i failed clip=$clipName", e)
                    } finally {
                        scaled?.recycle()
                        bitmap.recycle()
                    }
                }
                if (frames.isEmpty()) {
                    Log.w(
                        "Mp4Preview",
                        "generate: frames empty clip=$clipName sampleCount=$sampleCount width=$width height=$height durationUs=$durationUs",
                    )
                    return@runCatching null
                }
                if (frames.size == 1) {
                    Log.w(
                        "Mp4Preview",
                        "single frame clip=$clipName durationUs=$durationUs src=${srcWidth}x${srcHeight} → duplicating to 2 frames",
                    )
                    val first = frames[0]
                    frames.add(first.copy(argb = first.argb.copyOf()))
                }
                Log.i(
                    "Mp4Preview",
                    "encode start clip=$clipName frames=${frames.size} ${frames[0].width}x${frames[0].height} bytesEst=${frames.size * frames[0].width * frames[0].height}",
                )
                val bytes = try {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(60_000L) {
                            Mp4Encoder.encode(frames)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Mp4Preview", "encode failed clip=$clipName frames=${frames.size}", e)
                    return@runCatching null
                } ?: run {
                    Log.w("Mp4Preview", "encode timed out clip=$clipName frames=${frames.size}")
                    return@runCatching null
                }
                Log.i("Mp4Preview", "encode done clip=$clipName bytes=${bytes.size}")
                val stem = clipName.substringBeforeLast('.')
                val firstFrame = frames[0]
                val stillBmp = Bitmap.createBitmap(
                    firstFrame.argb, firstFrame.width, firstFrame.height,
                    Bitmap.Config.ARGB_8888,
                )
                val jpegBytes = try {
                    ByteArrayOutputStream().use { out ->
                        stillBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                } finally {
                    stillBmp.recycle()
                }
                if (jpegBytes.isEmpty()) {
                    Log.w("Mp4Preview", "still jpeg empty clip=$clipName")
                    return@runCatching null
                }
                Preview(
                    video = Snapshot(
                        bytes = bytes,
                        mimeType = "video/mp4",
                        name = "$stem.mp4",
                    ),
                    still = Snapshot(
                        bytes = jpegBytes,
                        mimeType = "image/jpeg",
                        name = "$stem.jpg",
                    ),
                )
            } finally {
                retriever.release()
            }
        }.onFailure { Log.w("Mp4Preview", "generate failed for $clipName", it) }.getOrNull()
    }

    private fun clipSource(name: String): ClipSource {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val collection = android.provider.MediaStore.Video.Media.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val uri = runCatching {
            resolver.query(
                collection,
                arrayOf(android.provider.MediaStore.Video.Media._ID),
                "${android.provider.MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(name),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
                } else null
            }
        }.getOrNull()
        if (uri != null) return ClipSource.Uri(uri)
        val fallback = File(appContext.filesDir, "videos/$name")
        return if (fallback.exists()) ClipSource.File(fallback.path) else ClipSource.Missing
    }

    private sealed interface ClipSource {
        data class Uri(val uri: android.net.Uri) : ClipSource
        data class File(val path: String) : ClipSource
        data object Missing : ClipSource
    }
}