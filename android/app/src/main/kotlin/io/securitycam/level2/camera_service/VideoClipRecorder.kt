package io.securitycam.level2.camera_service

import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import io.securitycam.level2.core.ClipStampPosition
import io.securitycam.level2.core.mediaFileName
import android.util.Log
import androidx.camera.video.PendingRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Pre/post-roll clip recording for the monitoring FGS (Android).
 *
 * A single CameraX `VideoCapture<Recorder>` writes pre-roll-length segments to
 * `cacheDir/video_segments/`; the last completed segment is kept as the ring
 * buffer. On a trigger the in-flight segment becomes the pre-roll tail, a
 * post-roll recording is started for the configured tail length, and the three
 * segments (ring + tail + post) are concatenated with MediaExtractor/MediaMuxer
 * into MediaStore `Movies/level2` with the shared date-time-cameraName
 * scheme.
 *
 * Audio: the recorder stays video-only; the native-owned mic's PCM is teed into
 * a bounded rolling [AudioPcmBuffer] via [onMicPcm]. At export the PCM slice
 * covering the clip window (from the pre-roll segment's wall-clock start over
 * the full concatenated duration) is AAC-encoded with MediaCodec and muxed in
 * as an audio track. If no PCM arrived or the AAC encode fails the clip falls
 * back to video-only — an export is never lost for lack of audio.
 *
 * `delete`/`open`/`exists` work without the FGS (they only touch MediaStore /
 * the app-private fallback), so retention purge and the Events screen can use
 * them whenever the app process is alive.
 */
object VideoClipRecorder {
    private const val TAG = "VideoClipRecorder"
    private const val AUDIO_SAMPLE_RATE = 16_000
    /**
     * Minimum age (ms) of an in-flight post-roll before [extendExport] /
     * [endExport] will stop it. Stopping a just-started recording — before
     * CameraX has encoded its first keyframe and started the muxer (took
     * ~240 ms in practice) — finalizes it as ERROR_NO_VALID_DATA with a
     * 0-byte file; the young guard defers the cut to the segment's natural
     * duration limit instead.
     */
    private const val POST_MIN_STOP_AGE_MS = 1_000L
    private val audioWindowSamples = 60_000L * AUDIO_SAMPLE_RATE / 1000L
    // App-lifetime scope: the recorder is a process-wide singleton serving
    // every monitoring session, so these executors are intentionally never
    // shut down.
    private val executor = Executors.newSingleThreadExecutor()
    private val exportExecutor = Executors.newSingleThreadExecutor()

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
    private var videoQuality = "lowest"
    private var clipTimestamp = false
    private var clipTimestampPosition = ClipStampPosition.bottomRight
    private var clipTimestampCameraName = false
    private var privacyMasking = false
    private var privacyMaskEffect = "solid"
    private var exclusionZones: List<io.securitycam.level2.detection.DetectionZone> = emptyList()

    @Volatile private var active = false
    @Volatile private var exporting = false

    /**
     * Orientation authored into exported clips (degrees). Set at bind time to
     * `(sensorOrientation - videoTargetRotation + 360) % 360` — exactly what
     * CameraX wrote into the per-segment headers, which the MediaMuxer
     * re-mux would otherwise discard.
     */
    @Volatile private var orientationHintDegrees: Int = 0

    fun setOrientationHintDegrees(degrees: Int) {
        orientationHintDegrees = ((degrees % 360) + 360) % 360
    }

    // Export coordination: on a trigger the current ring segment is stopped;
    // its Finalize becomes the pre-roll tail, then a post-roll recording is
    // started whose Finalize runs the concat + MediaStore insert. While a
    // wave keeps triggering (or shorter than the merge window), fresh post-roll
    // segments keep chaining so the clip stays live until [endExport] is
    // called.
    @Volatile private var exportPending = false
    @Volatile private var postRollPending = false
    /**
     * True once [endExport] ran (the batch closed = the wave is over): the
     * clip must finalize. While false and monitoring is still active, a
     * duration-limited post-roll that finalizes on its own (no trigger since)
     * chains the next one instead of completing early — otherwise a wave whose
     * triggers are spaced past the post-roll length (the 5s detector cooldown
     * vs 5s post-roll) would freeze the clip at its first segment and the rest
     * of the wave would join the same batch with no video.
     */
    @Volatile private var exportClosing = false
    /**
     * Monotonic start time of the in-flight post-roll (elapsedRealtime, ms).
     * Set when a post-roll recording begins; [extendExport]/[endExport] skip
     * stopping it until it is [POST_MIN_STOP_AGE_MS] old.
     */
    @Volatile private var postStartWallMs = 0L
    private var preFile: File? = null
    private var tailFile: File? = null
    private val postFiles = ArrayList<File>()
    private var exportTriggerMs = 0L
    private var exportResult: ((String?) -> Unit)? = null

    /** An export request that arrived while another was in flight. */
    private class PendingExport(
        val triggerAtMs: Long,
        val preRollSeconds: Int,
        val postRollSeconds: Int,
        val camName: String,
        val result: (String?) -> Unit,
    )

    /** Queued exports drained in order after the current one completes. */
    private val pendingExports = java.util.ArrayDeque<PendingExport>()

    // Audio for clip muxing: mic timeline -> wall clock, per-segment wall starts,
    // and the rolling PCM buffer fed by the native-owned mic.
    @Volatile private var micStartWallMicros = 0L
    private val segmentStartWallMicros = ConcurrentHashMap<String, Long>()
    private val audioPcm = AudioPcmBuffer(AUDIO_SAMPLE_RATE, audioWindowSamples)

    /**
     * Binds the application context so holder-based helpers ([open], [exists],
     * [delete], ...) work from process start, before any monitoring session.
     * [configure] re-binds with recording parameters when monitoring starts.
     */
    fun attach(ctx: Context) {
        context = ctx.applicationContext
        ClipMediaStore.attach(ctx)
    }

    /** Media-store reads/writes work without the recording session (facade). */
    fun delete(name: String) = ClipMediaStore.delete(name)
    fun exists(name: String): Boolean = ClipMediaStore.exists(name)
    fun openStream(name: String): java.io.InputStream? = ClipMediaStore.openStream(name)
    fun hasAudio(name: String): Boolean = ClipMediaStore.hasAudio(name)
    fun videoInfo(name: String): Map<String, Int>? = ClipMediaStore.videoInfo(name)
    fun open(name: String): String? = ClipMediaStore.open(name)

    fun configure(
        ctx: Context,
        camName: String,
        preRollSeconds: Int,
        postRollSeconds: Int,
        videoQuality: String,
        clipTimestamp: Boolean = false,
        clipTimestampPosition: String = ClipStampPosition.bottomRight,
        clipTimestampCameraName: Boolean = false,
        privacyMasking: Boolean = false,
        privacyMaskEffect: String = "solid",
        exclusionZones: List<io.securitycam.level2.detection.DetectionZone> = emptyList(),
    ) {
        context = ctx.applicationContext
        cameraName = camName
        segmentMs = preRollSeconds.coerceAtLeast(1) * 1000L
        postRollMs = postRollSeconds.coerceAtLeast(1) * 1000L
        this.videoQuality = videoQuality
        this.clipTimestamp = clipTimestamp
        this.clipTimestampPosition = clipTimestampPosition
        this.clipTimestampCameraName = clipTimestampCameraName
        this.privacyMasking = privacyMasking
        this.privacyMaskEffect = privacyMaskEffect
        this.exclusionZones = exclusionZones
        val dir = File(ctx.applicationContext.cacheDir, "video_segments")
        if (!dir.exists()) dir.mkdirs()
        ringDir = dir
    }

    internal fun mapQuality(value: String): Quality = when (value) {
        "sd" -> Quality.SD
        "hd" -> Quality.HD
        "fhd" -> Quality.FHD
        "uhd" -> Quality.UHD
        "highest" -> Quality.HIGHEST
        else -> Quality.LOWEST
    }

    /**
     * Builds the video use case for the CameraX bind. [rotation] is the current
     * display rotation (a `Surface.ROTATION_*` constant) so clips are recorded
     * upright regardless of the device's sensor orientation.
     */
    fun buildVideoCapture(rotation: Int): VideoCapture<Recorder> {
        val quality = mapQuality(videoQuality)
        val selector = QualitySelector.from(
            quality,
            FallbackStrategy.lowerQualityOrHigherThan(quality),
        )
        val r = Recorder.Builder()
            .setExecutor(executor)
            .setQualitySelector(selector)
            .build()
        recorder = r
        videoCapture = VideoCapture.Builder(r)
            .setTargetRotation(rotation)
            .build()
        return videoCapture!!
    }

    fun onMonitoringStarted() {
        Log.i(TAG, "onMonitoringStarted: active=true ringDir=${ringDir != null} recorder=${recorder != null}")
        active = true
        startRingRecording()
    }

    fun onMonitoringStopped() {
        Log.i(TAG, "onMonitoringStopped: was active=$active exporting=$exporting")
        active = false
        stopRingRecording()
        if (!exporting) {
            clearTempFiles()
            audioPcm.clear()
            segmentStartWallMicros.clear()
            micStartWallMicros = 0L
        }
    }

    /** Monotonic wall clock (µs) used to align audio slices with video segments. */
    private fun wallMicros() = SystemClock.elapsedRealtimeNanos() / 1000L

    /**
     * Mic PCM callback (native-owned mic read thread). Records the mic timeline
     * origin on the first chunk and tees the PCM into the rolling clip buffer.
     */
    fun onMicPcm(pcm: ByteArray, startSample: Long) {
        if (micStartWallMicros == 0L) {
            micStartWallMicros = wallMicros()
        }
        audioPcm.add(pcm, startSample)
    }

    /**
     * Audio slice start on the mic timeline (µs since mic start) for the current
     * export, or null when there is no mic timeline yet (video-only fallback).
     */
    private fun audioStartMicros(): Long? {
        if (micStartWallMicros == 0L) return null
        val preStartFile = tailFile ?: preFile ?: return null
        val startWall = segmentStartWallMicros[preStartFile.path] ?: return null
        return startWall - micStartWallMicros
    }

    /** Starts the pre-roll ring loop. Called from the FGS start/bind path. */
    private fun startRingRecording() {
        val currentRecorder = recorder ?: return
        val dir = ringDir ?: return
        val appContext = context ?: run {
            Log.w(TAG, "ring start skipped: no application context")
            return
        }
        val file = File(dir, "seg-${System.currentTimeMillis()}.mp4")
        try {
            val options = androidx.camera.video.FileOutputOptions.Builder(file)
                .setDurationLimitMillis(segmentMs)
                .build()
            // Drop the superseded ring-segment key: only the latest completed
            // segment is retained, so stale wall-clock entries must not linger
            // and poison the audio-alignment lookup.
            ringSegment?.let { old ->
                if (old.path != file.path) segmentStartWallMicros.remove(old.path)
            }
            segmentStartWallMicros[file.path] = wallMicros()
            ringRecording = currentRecorder.prepareRecording(appContext, options)
                .start(executor) { event -> handleRingEvent(event, file) }
        } catch (e: Exception) {
            Log.w(TAG, "ring start failed", e)
            segmentStartWallMicros.remove(file.path)
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
                // Dropped a segment with no valid data (e.g. stopped before its
                // first keyframe → ERROR_NO_VALID_DATA). Never kill the clip
                // over a dead sliver: chain a replacement while the batch may
                // still be open, else finalize with what we have.
                postRollPending = false
                if (active && !exportClosing) {
                    startPostRollRecording()
                } else {
                    completeExport()
                }
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
                postFiles.add(file)
                // Defensive branch: the post-roll normally finalizes through
                // its own inline callback, not here. Chain while the batch may
                // still be open.
                if (active && !exportClosing) {
                    startPostRollRecording()
                } else {
                    completeExport()
                }
            }
            exportPending -> {
                exportPending = false
                tailFile = file
                startPostRollRecording()
            }
            else -> {
                // Superseded ring segment: evict its wall-clock key so the
                // audio-alignment map only ever references the live segment.
                ringSegment?.let { old ->
                    if (old.path != file.path) segmentStartWallMicros.remove(old.path)
                }
                ringSegment = file
                if (active) startRingRecording()
            }
        }
    }

    /**
     * Captures the pre-roll ring plus [postRollSeconds] of footage and stores
     * the clip; [result] receives the display name, or null when unavailable
     * (not monitoring). While a wave keeps triggering, [extendExport] chains
     * more post-roll segments into the same clip, and [endExport] finalizes
     * it; a clip ever couple of triggers, so close events are never lost.
     * When an export is already in flight the request is queued and served
     * after it completes.
     */
    fun exportClip(
        triggerAtMs: Long,
        preRollSeconds: Int,
        postRollSeconds: Int,
        camName: String,
        result: (String?) -> Unit,
    ) {
        if (!active || recorder == null || ringDir == null) {
            Log.w(TAG, "exportClip rejected: active=$active " +
                "recorder=${recorder != null} ringDir=${ringDir != null} triggerAt=$triggerAtMs")
            result(null)
            return
        }
        if (exporting) {
            Log.i(
                TAG, "exportClip queued behind in-flight export " +
                    "(triggerAt=$triggerAtMs queueDepth=${pendingExports.size + 1})",
            )
            pendingExports.addLast(
                PendingExport(triggerAtMs, preRollSeconds, postRollSeconds, camName, result),
            )
            return
        }
        Log.i(
            TAG, "exportClip begin triggerAt=$triggerAtMs pre=${preRollSeconds}s " +
                "post=${postRollSeconds}s cam=$camName ringSeg=${ringSegment?.name} " +
                "preFile=${ringSegment?.name} ringDir=${ringDir?.name}",
        )
        beginExport(triggerAtMs, preRollSeconds, postRollSeconds, camName, result)
    }

    private fun beginExport(
        triggerAtMs: Long,
        preRollSeconds: Int,
        postRollSeconds: Int,
        camName: String,
        result: (String?) -> Unit,
    ) {
        exporting = true
        exportClosing = false
        exportTriggerMs = triggerAtMs
        cameraName = camName
        exportResult = result
        preFile = ringSegment
        postFiles.clear()
        val current = ringRecording
        if (current == null) {
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

    /**
     * Keeps the current clip open while its wave keeps triggering: chains
     * fresh post-roll segments (stopping the tail in flight so a new one
     * starts from the newest trigger) until [endExport] finalizes it.
     */
    fun extendExport() {
        if (!active || !exporting) return
        exportClosing = false
        if (postRollPending && postRollIsMature()) {
            try {
                postRecording?.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** Wave over: stop extending so the in-flight tail finalizes and exports. */
    fun endExport() {
        if (!exporting) return
        exportClosing = true
        if (postRollPending && postRollIsMature()) {
            try {
                postRecording?.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** True once the in-flight post-roll has produced its first frames and can
     *  be cut without risking a no-valid-data (empty) finalize. */
    private fun postRollIsMature(): Boolean =
        SystemClock.elapsedRealtime() - postStartWallMs >= POST_MIN_STOP_AGE_MS

    private fun startPostRollRecording() {
        val currentRecorder = recorder ?: run {
            Log.w(TAG, "post-roll start failed: recorder null (active=$active)")
            failExport(); return
        }
        val dir = ringDir ?: run {
            Log.w(TAG, "post-roll start failed: ringDir null (active=$active)")
            failExport(); return
        }
        val appContext = context ?: run {
            Log.w(TAG, "post-roll start skipped: no application context")
            failExport()
            return
        }
        val file = File(dir, "post-${System.currentTimeMillis()}.mp4")
        postRollPending = true
        try {
            val options = androidx.camera.video.FileOutputOptions.Builder(file)
                .setDurationLimitMillis(postRollMs)
                .build()
            segmentStartWallMicros[file.path] = wallMicros()
            postRecording = currentRecorder.prepareRecording(appContext, options)
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        postRecording = null
                        postRollPending = false
                        if (!file.exists() || file.length() == 0L) {
                            // No valid data (e.g. a stop landed before the first
                            // keyframe → ERROR_NO_VALID_DATA). Dropping a dead
                            // sliver must never kill the clip: chain a
                            // replacement while the batch may still be open,
                            // else finalize with the segments we have.
                            file.delete()
                            Log.w(TAG, "post-roll had no valid data: chaining/complete")
                            if (active && !exportClosing) {
                                startPostRollRecording()
                            } else {
                                completeExport()
                            }
                        } else if (active && !exportClosing) {
                            postFiles.add(file)
                            startPostRollRecording()
                        } else {
                            postFiles.add(file)
                            completeExport()
                        }
                    }
                }
            postStartWallMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            postRollPending = false
            postRecording = null
            file.delete()
            failExport()
        }
    }

    private fun completeExport() {
        val name = videoFileName(exportTriggerMs, cameraName)
        val inputs = (listOfNotNull(preFile, tailFile) + postFiles)
            .filter { it.exists() && it.length() > 0L }
        val audioStart = audioStartMicros()
        Log.i(
            TAG, "completeExport name=$name triggerAt=$exportTriggerMs " +
                "inputs=${inputs.size} (pre=${preFile?.let { "${it.name}:${it.length()}" }} " +
                "tail=${tailFile?.let { "${it.name}:${it.length()}" }} post=${postFiles.size}) " +
                "audioStart=${audioStart != null}",
        )
        if (inputs.isEmpty()) {
            Log.w(
                TAG, "completeExport no valid inputs " +
                    "(pre=${preFile?.exists()}:${preFile?.length()} " +
                    "tail=${tailFile?.exists()}:${tailFile?.length()} post=${postFiles.map { "${it.name}:${it.length()}" }})",
            )
        }
        val finalFile = File(ringDir, "final-${System.currentTimeMillis()}.mp4")
        // Heavy work (video+audio mux, AAC encode, MediaStore write) runs off the
        // CameraX executor so the ring loop and Finalize events are never starved
        // by a slow export (notably on constrained emulators). State is only
        // mutated back on the camera executor in [finishExport].
        exportExecutor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            val stored = try {
                if (inputs.isEmpty()) {
                    null
                } else {
                    val audio = audioStart?.let { start ->
                        MuxAudio(audioPcm, AUDIO_SAMPLE_RATE, start)
                    }
                    ClipMuxer.muxClip(inputs, finalFile, audio, orientationHintDegrees)
                    // Burn the date/time stamp and/or privacy mask when enabled.
                    // The clip starts preRoll before the trigger, so frame
                    // wall-clock = trigger − preRoll + presentation. Any
                    // stamping/masking failure falls back to the unstamped
                    // clip — never lose evidence.
                    var storeFile = finalFile
                    val needsStamp = clipTimestamp
                    val needsMask = privacyMasking && exclusionZones.isNotEmpty()
                    if (needsStamp || needsMask) {
                        val stamped = File(ringDir, "stamped-${System.currentTimeMillis()}.mp4")
                        val appContext = context
                        val ok = if (appContext != null) {
                            ClipStamper.stamp(
                                context = appContext,
                                input = finalFile,
                                output = stamped,
                                startWallMs = exportTriggerMs - segmentMs,
                                position = clipTimestampPosition,
                                includeCameraName = clipTimestampCameraName,
                                cameraName = cameraName,
                                exclusionZones = exclusionZones,
                                privacyMaskEffect = privacyMaskEffect,
                            )
                        } else {
                            false
                        }
                        if (ok) {
                            storeFile = stamped
                        } else {
                            Log.w(TAG, "stamping failed; storing unstamped clip")
                            stamped.delete()
                        }
                    }
                    ClipMediaStore.storeInMediaStore(storeFile, name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "export failed", e)
                null
            } finally {
                Log.i(TAG, "export took ${SystemClock.elapsedRealtime() - startedAt}ms")
            }
            executor.execute {
                finishExport(name, stored, inputs, finalFile)
            }
        }
    }

    private fun finishExport(
        name: String,
        stored: String?,
        inputs: List<File>,
        finalFile: File,
    ) {
        if (stored != null) {
            Log.i(TAG, "exported clip $name (${inputs.size} segments, audio=${
                audioPcm.lastSample > 0L
            })")
        }
        deleteQuietly(*inputs.toTypedArray(), finalFile)
        for (f in inputs) {
            segmentStartWallMicros.remove(f.path)
        }
        preFile = null
        tailFile = null
        postFiles.clear()
        ringSegment = null
        val cb = exportResult
        exportResult = null
        exporting = false
        cb?.invoke(stored)
        // Serve any export queued while this one was recording — the wave
        // that hit during the tail must still keep its clip. The ring loop
        // only restarts when no export is left to run.
        val next = pendingExports.pollFirst()
        if (next != null && active && recorder != null && ringDir != null) {
            beginExport(next.triggerAtMs, next.preRollSeconds, next.postRollSeconds, next.camName, next.result)
        } else if (active) {
            startRingRecording()
        }
    }

    private fun failExport() {
        Log.w(TAG, "failExport: cleaning up pre=${preFile?.name} tail=${tailFile?.name} post=${postFiles.size} files")
        deleteQuietly(preFile, tailFile, *postFiles.toTypedArray())
        preFile = null
        tailFile = null
        postFiles.clear()
        val cb = exportResult
        exportResult = null
        exporting = false
        cb?.invoke(null)
        val next = pendingExports.pollFirst()
        if (next != null && active && recorder != null && ringDir != null) {
            beginExport(next.triggerAtMs, next.preRollSeconds, next.postRollSeconds, next.camName, next.result)
        } else if (active) {
            startRingRecording()
        }
    }

    private fun clearTempFiles() {
        val dir = ringDir ?: return
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("seg-") ||
                file.name.startsWith("post-") ||
                file.name.startsWith("final-") ||
                file.name.startsWith("stamped-")
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

    /** Shared date-time-cameraName scheme via [mediaFileName]. */
    internal fun videoFileName(triggerAtMs: Long, camName: String): String =
        mediaFileName(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMs), ZoneId.systemDefault()),
            camName,
            "mp4",
        )
}
