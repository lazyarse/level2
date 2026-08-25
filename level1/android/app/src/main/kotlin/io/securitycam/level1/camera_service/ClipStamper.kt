package io.securitycam.level1.camera_service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Burns the date/time stamp into a concatenated clip via Media3 Transformer
 * (hardware codecs, audio passthrough, rotation-metadata aware). Blocking;
 * returns false on any failure so the caller can fall back to the unstamped
 * clip rather than losing it.
 */
object ClipStamper {

    private const val TAG = "ClipStamper"
    private const val TIMEOUT_SECONDS = 120L

    fun stamp(
        context: Context,
        input: File,
        output: File,
        startWallMs: Long,
        position: String,
        includeCameraName: Boolean,
        cameraName: String,
    ): Boolean {
        val done = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val thread = HandlerThread("ClipStamper").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val (frameW, frameH) = videoSize(input)
            handler.post {
                val overlay = StampOverlay(
                    startWallMs, position, includeCameraName, cameraName, frameW, frameH,
                )
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                    ) {
                        success.set(true)
                        done.countDown()
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        error: ExportException,
                    ) {
                        Log.w(TAG, "stamp export failed", error)
                        done.countDown()
                    }
                })
                val effects = Effects(
                    emptyList(),
                    listOf(OverlayEffect(ImmutableList.of(overlay))),
                )
                val item = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(input)),
                )
                    .setEffects(effects)
                    .build()
                transformer.start(item, output.absolutePath)
            }
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "stamp export timed out")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stamping threw", t)
        } finally {
            thread.quitSafely()
        }
        return success.get() && output.exists() && output.length() > 0
    }

    /** Decoded pixel size of the concatenated clip (fallback 1920x1080). */
    private fun videoSize(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            // Stored frames are pre-rotation pixels; stamp in that space.
            if (rotation == 90 || rotation == 270) h to w else w to h
        } catch (_: Exception) {
            1920 to 1080
        } finally {
            retriever.release()
        }
    }
}

/**
 * Full-frame stamp overlay: the bitmap matches the video's pixel size so the
 * corner placement lands exactly where [TimestampStamp.draw] puts it. Rebuilt
 * at most once per second (timestamp granularity).
 */
class StampOverlay(
    private val startWallMs: Long,
    private val position: String,
    private val includeCameraName: Boolean,
    private val cameraName: String,
    private val frameWidth: Int,
    private val frameHeight: Int,
) : BitmapOverlay() {

    private var cached: Bitmap? = null
    private var cachedSecond = Long.MIN_VALUE

    override fun getBitmap(presentationUs: Long): Bitmap {
        val wallMs = startWallMs + presentationUs / 1000
        val second = wallMs / 1000
        val bmp = cached
        if (bmp != null && cachedSecond == second) return bmp
        val out = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        TimestampStamp.draw(
            Canvas(out),
            wallMs = wallMs,
            position = position,
            width = frameWidth,
            height = frameHeight,
            includeCameraName = includeCameraName,
            cameraName = cameraName,
        )
        cached = out
        cachedSecond = second
        return out
    }
}
