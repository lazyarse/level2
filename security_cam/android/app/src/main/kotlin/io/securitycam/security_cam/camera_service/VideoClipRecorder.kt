package io.securitycam.security_cam.camera_service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Pre/post-roll clip recording for the monitoring FGS (Android).
 *
 * A single CameraX `VideoCapture<Recorder>` writes pre-roll-length segments to
 * `cacheDir/video_segments/`; the last completed segment is kept as the ring
 * buffer. On a trigger the in-flight segment becomes the pre-roll tail, a
 * post-roll recording is started for the configured tail length, and the three
 * segments (ring + tail + post) are concatenated with MediaExtractor/MediaMuxer
 * into MediaStore `Movies/SecurityCam` with the shared date-time-cameraName
 * scheme. Audio is not recorded (the analysis path owns the microphone).
 *
 * `delete`/`open`/`exists` work without the FGS (they only touch MediaStore /
 * the app-private fallback), so retention purge and the Events screen can use
 * them whenever the app process is alive.
 */
object VideoClipRecorder {
    private const val TAG = "VideoClipRecorder"
    private val executor = Executors.newSingleThreadExecutor()

    private var context: Context? = null
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var ringRecording: Recording? = null
    private var postRecording: Recording? = null
    private var ringSegment: File? = null
    private var ringDir: File? = null
    private var segmentMs = 5_000L
    private var postRollMs = 5_000L
    private var cameraName = "Hallway"

    @Volatile private var active = false
    @Volatile private var exporting = false

    // Export coordination: on a trigger the current ring segment is stopped;
    // its Finalize becomes the pre-roll tail, then a post-roll recording is
    // started whose Finalize runs the concat + MediaStore insert.
    @Volatile private var exportPending = false
    @Volatile private var postRollPending = false
    private var preFile: File? = null
    private var tailFile: File? = null
    private var postFile: File? = null
    private var exportTriggerMs = 0L
    private var exportResult: ((String?) -> Unit)? = null

    fun configure(
        ctx: Context,
        camName: String,
        preRollSeconds: Int,
        postRollSeconds: Int,
    ) {
        context = ctx.applicationContext
        cameraName = camName
        segmentMs = preRollSeconds.coerceAtLeast(1) * 1000L
        postRollMs = postRollSeconds.coerceAtLeast(1) * 1000L
        val dir = File(ctx.applicationContext.cacheDir, "video_segments")
        if (!dir.exists()) dir.mkdirs()
        ringDir = dir
    }

    /** Builds the video use case for the CameraX bind. */
    fun buildVideoCapture(): VideoCapture<Recorder> {
        val r = Recorder.Builder()
            .setExecutor(executor)
            .setQualitySelector(QualitySelector.from(Quality.LOWEST))
            .build()
        recorder = r
        videoCapture = VideoCapture.withOutput(r)
        return videoCapture!!
    }

    fun onMonitoringStarted() {
        active = true
        startRingRecording()
    }

    fun onMonitoringStopped() {
        active = false
        stopRingRecording()
        if (!exporting) clearTempFiles()
    }

    /** Starts the pre-roll ring loop. Called from the FGS start/bind path. */
    private fun startRingRecording() {
        val currentRecorder = recorder ?: return
        val dir = ringDir ?: return
        val file = File(dir, "seg-${System.currentTimeMillis()}.mp4")
        try {
            val options = androidx.camera.video.FileOutputOptions.Builder(file)
                .setDurationLimitMillis(segmentMs)
                .build()
            ringRecording = currentRecorder.prepareRecording(context!!, options)
                .start(executor) { event -> handleRingEvent(event, file) }
        } catch (e: Exception) {
            Log.w(TAG, "ring start failed", e)
            file.delete()
        }
    }

    private fun stopRingRecording() {
        try {
            ringRecording?.stop()
        } catch (_: Exception) {
        }
        ringRecording = null
        try {
            postRecording?.stop()
        } catch (_: Exception) {
        }
        postRecording = null
    }

    private fun handleRingEvent(event: VideoRecordEvent, file: File) {
        if (event is VideoRecordEvent.Start) return
        if (event !is VideoRecordEvent.Finalize) return
        val ok = file.exists() && file.length() > 0L
        if (!ok) {
            file.delete()
            if (postRollPending) {
                postRollPending = false
                failExport()
            } else if (exportPending) {
                exportPending = false
                startPostRollRecording()
            } else if (active) {
                startRingRecording()
            }
            return
        }
        when {
            postRollPending -> {
                postRollPending = false
                postFile = file
                completeExport()
            }
            exportPending -> {
                exportPending = false
                tailFile = file
                startPostRollRecording()
            }
            else -> {
                ringSegment = file
                if (active) startRingRecording()
            }
        }
    }

    /**
     * Captures the pre-roll ring plus [postRollSeconds] of footage and stores
     * the clip; [result] receives the display name, or null when unavailable
     * (not monitoring, or an export already in progress).
     */
    fun exportClip(
        triggerAtMs: Long,
        preRollSeconds: Int,
        postRollSeconds: Int,
        camName: String,
        result: (String?) -> Unit,
    ) {
        val currentRecorder = recorder
        if (!active || exporting || currentRecorder == null || ringDir == null) {
            result(null)
            return
        }
        exporting = true
        exportTriggerMs = triggerAtMs
        cameraName = camName
        exportResult = result
        val current = ringRecording
        if (current == null) {
            preFile = ringSegment
            tailFile = null
            startPostRollRecording()
        } else {
            exportPending = true
            try {
                current.stop()
            } catch (e: Exception) {
                exportPending = false
                failExport()
            }
        }
    }

    private fun startPostRollRecording() {
        val currentRecorder = recorder ?: run { failExport(); return }
        val dir = ringDir ?: run { failExport(); return }
        val file = File(dir, "post-${System.currentTimeMillis()}.mp4")
        postRollPending = true
        try {
            val options = androidx.camera.video.FileOutputOptions.Builder(file)
                .setDurationLimitMillis(postRollMs)
                .build()
            postRecording = currentRecorder.prepareRecording(context!!, options)
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        postRecording = null
                        postRollPending = false
                        if (!file.exists() || file.length() == 0L) {
                            file.delete()
                            failExport()
                        } else {
                            postFile = file
                            completeExport()
                        }
                    }
                }
        } catch (e: Exception) {
            postRollPending = false
            postRecording = null
            file.delete()
            failExport()
        }
    }

    private fun completeExport() {
        val name = videoFileName(exportTriggerMs, cameraName)
        val inputs = listOfNotNull(preFile, tailFile, postFile)
            .filter { it.exists() && it.length() > 0L }
        val finalFile = File(ringDir, "final-${System.currentTimeMillis()}.mp4")
        val stored = try {
            if (inputs.isEmpty()) {
                null
            } else {
                concatMp4(inputs, finalFile)
                storeInMediaStore(finalFile, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            null
        }
        if (stored != null) {
            Log.i(TAG, "exported clip $name (${inputs.size} segments)")
        }
        deleteQuietly(preFile, tailFile, postFile, finalFile)
        preFile = null
        tailFile = null
        postFile = null
        ringSegment = null
        val cb = exportResult
        exportResult = null
        exporting = false
        cb?.invoke(stored)
        if (active) startRingRecording()
    }

    private fun failExport() {
        deleteQuietly(preFile, tailFile, postFile)
        preFile = null
        tailFile = null
        postFile = null
        val cb = exportResult
        exportResult = null
        exporting = false
        cb?.invoke(null)
        if (active) startRingRecording()
    }

    private fun clearTempFiles() {
        val dir = ringDir ?: return
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("seg-") ||
                file.name.startsWith("post-") ||
                file.name.startsWith("final-")
            ) {
                file.delete()
            }
        }
    }

    private fun deleteQuietly(vararg files: File?) {
        for (f in files) {
            try {
                f?.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Shared date-time-cameraName scheme (mirrors Dart `mediaFileName`). */
    private fun videoFileName(triggerAtMs: Long, camName: String): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        fun three(n: Int) = n.toString().padStart(3, '0')
        val t = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
        val date = "${t.get(Calendar.YEAR)}-${two(t.get(Calendar.MONTH) + 1)}-" +
            two(t.get(Calendar.DAY_OF_MONTH))
        val time = "${two(t.get(Calendar.HOUR_OF_DAY))}-" +
            "${two(t.get(Calendar.MINUTE))}-${two(t.get(Calendar.SECOND))}-" +
            three(t.get(Calendar.MILLISECOND))
        val safe = camName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${date}_${time}_$safe.mp4"
    }

    /** Concatenates video-only MP4s, offsetting timestamps by real durations. */
    private fun concatMp4(inputs: List<File>, output: File) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var offsetUs = 0L
        try {
            for (input in inputs) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(input.path)
                    var srcTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                            srcTrack = i
                            break
                        }
                    }
                    if (srcTrack < 0) continue
                    extractor.selectTrack(srcTrack)
                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(extractor.getTrackFormat(srcTrack))
                        muxer.start()
                    }
                    val buffer = ByteBuffer.allocate(256 * 1024)
                    var lastSampleTimeUs = 0L
                    val bufferInfo = android.media.MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0) break
                        lastSampleTimeUs = sampleTime
                        bufferInfo.offset = 0
                        bufferInfo.size = size
                        bufferInfo.presentationTimeUs = sampleTime + offsetUs
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    if (lastSampleTimeUs > 0L) {
                        offsetUs += lastSampleTimeUs
                    } else {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(input.path)
                            offsetUs += (retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L) * 1000L
                        } finally {
                            retriever.release()
                        }
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            if (trackIndex >= 0) {
                muxer.stop()
            }
            muxer.release()
        }
        if (trackIndex < 0) throw IllegalStateException("no video track to concatenate")
    }

    /** Writes the final clip into the gallery (MediaStore 29+, DATA on 24-28). */
    private fun storeInMediaStore(source: File, displayName: String): String? {
        val appContext = context ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/SecurityCam"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return null
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: run {
                    appContext.contentResolver.delete(uri, null, null)
                    return null
                }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                appContext.contentResolver.update(uri, done, null, null)
                displayName
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "SecurityCam"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, displayName)
                source.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATA, dest.absolutePath)
                }
                appContext.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                displayName
            }
        } catch (e: Exception) {
            Log.w(TAG, "media store insert failed, falling back to app-private", e)
            try {
                val dest = File(appContext.filesDir, "videos/$displayName")
                dest.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                displayName
            } catch (e2: Exception) {
                Log.w(TAG, "app-private fallback failed", e2)
                null
            }
        }
    }

    private fun queryUriByName(name: String): Uri? {
        val appContext = context ?: return null
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            appContext.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(name),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    Uri.withAppendedPath(collection, c.getLong(0).toString())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(name: String) {
        val appContext = context ?: return
        val uri = queryUriByName(name)
        if (uri != null) {
            try {
                appContext.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "media delete failed for $name", e)
            }
        }
        val fallback = File(appContext.filesDir, "videos/$name")
        if (fallback.exists()) fallback.delete()
    }

    fun exists(name: String): Boolean {
        val appContext = context ?: return false
        if (queryUriByName(name) != null) return true
        return File(appContext.filesDir, "videos/$name").exists()
    }

    fun open(name: String) {
        val appContext = context ?: return
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        val contentUri = if (uri != null) {
            uri
        } else if (fallback.exists()) {
            FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", fallback
            )
        } else {
            Log.w(TAG, "open video: no such clip $name")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open video failed for $name", e)
        }
    }
}
