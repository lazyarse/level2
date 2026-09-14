package io.securitycam.level2.camera_service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private const val AUDIO_FRAME_SAMPLES = 1024
    private const val AUDIO_BIT_RATE = 48_000

    /** AAC input dequeue timeout (µs): bounds the old zero-timeout busy-spin. */
    internal const val INPUT_TIMEOUT_US = 10_000L

    /** Max EOS input retries before failing the encode instead of looping forever. */
    private const val MAX_EOS_SPINS = 500
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
    }

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
        if (!active || !exporting) return
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
                    muxClip(inputs, finalFile, audioStart)
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
                    storeInMediaStore(storeFile, name)
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

    /**
     * samples*2 clamped to Int range for ByteArray sizing (a multi-minute
     * window can never exceed it in practice, but a corrupt timeline must not
     * wrap to a negative size and crash the export thread).
     */
    internal fun clampSamplesToBytes(samples: Long): Int =
        (samples.coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()

    /** Shared date-time-cameraName scheme via [mediaFileName]. */
    internal fun videoFileName(triggerAtMs: Long, camName: String): String =
        mediaFileName(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMs), ZoneId.systemDefault()),
            camName,
            "mp4",
        )

    /**
     * Muxes the concatenated video segments plus (when [audioStartMicros] maps a
     * non-empty PCM slice) an AAC audio track into [output]. The audio timeline
     * starts at 0 (aligned with the first video frame); the slice runs from the
     * pre-roll start over the full concatenated duration. Falls back to
     * video-only when audio is unavailable or the encode fails.
     */
    private fun muxClip(inputs: List<File>, output: File, audioStartMicros: Long?) {
        val t0 = SystemClock.elapsedRealtime()
        val totalVideoUs = videoDurationUs(inputs)
        var audio: AacFrames? = null
        if (audioStartMicros != null && totalVideoUs > 0L) {
            val rate = AUDIO_SAMPLE_RATE
            val rawStartSample = audioStartMicros * rate / 1_000_000L
            val startSample = maxOf(0L, rawStartSample)
            val endSample = maxOf(startSample, (audioStartMicros + totalVideoUs) * rate / 1_000_000L)
            val prefixSamples = maxOf(0L, -rawStartSample)
            val slice = audioPcm.slice(startSample, endSample)
            if (slice.isNotEmpty() || prefixSamples > 0L) {
                val prefixBytes = clampSamplesToBytes(prefixSamples)
                val combined = ByteArray(slice.size + prefixBytes)
                System.arraycopy(slice, 0, combined, prefixBytes, slice.size)
                val t1 = SystemClock.elapsedRealtime()
                audio = try {
                    encodeAac(combined)
                } catch (e: Exception) {
                    Log.w(TAG, "AAC encode failed; clip stays video-only", e)
                    null
                }
                Log.i(TAG, "aac encode: ${combined.size / 2} samples in ${SystemClock.elapsedRealtime() - t1}ms")
            }
        }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // Segment re-muxing strips the per-segment rotation metadata CameraX
        // wrote; re-apply the authored orientation so exports play upright.
        muxer.setOrientationHint(orientationHintDegrees)
        val aac = audio
        var videoTrack = -1
        var audioTrack = -1
        try {
            videoTrack = addVideoTrack(muxer, inputs)
            if (aac != null && aac.frames.isNotEmpty()) {
                val af = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_SAMPLE_RATE, 1)
                af.setByteBuffer("csd-0", aac.csd0)
                audioTrack = muxer.addTrack(af)
            }
            muxer.start()
            writeVideoSamples(muxer, videoTrack, inputs)
            if (audioTrack >= 0) {
                val info = MediaCodec.BufferInfo()
                for ((buf, pts) in aac!!.frames) {
                    info.offset = 0
                    info.size = buf.remaining()
                    info.presentationTimeUs = pts
                    info.flags = 0
                    muxer.writeSampleData(audioTrack, buf, info)
                }
            }
        } finally {
            if (videoTrack >= 0) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            muxer.release()
        }
        if (videoTrack < 0) throw IllegalStateException("no video track to concatenate")
        Log.i(TAG, "mux complete in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    /** Total real video duration (µs) across [inputs], via last-sample times. */
    private fun videoDurationUs(inputs: List<File>): Long {
        var totalUs = 0L
        for (input in inputs) {
            var firstSampleTimeUs = -1L
            var lastSampleTimeUs = -1L
            var sampleCount = 0L
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
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTime
                    lastSampleTimeUs = sampleTime
                    sampleCount++
                    extractor.advance()
                }
            } finally {
                extractor.release()
            }
            if (lastSampleTimeUs >= 0 && firstSampleTimeUs >= 0) {
                totalUs += concatSegmentDurationUs(firstSampleTimeUs, lastSampleTimeUs, sampleCount)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(input.path)
                    totalUs += (retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L) * 1000L
                } finally {
                    retriever.release()
                }
            }
        }
        return totalUs
    }

    /** Registers the video track from the first input that has one. */
    private fun addVideoTrack(muxer: MediaMuxer, inputs: List<File>): Int {
        for (input in inputs) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.path)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        return muxer.addTrack(fmt)
                    }
                }
            } finally {
                extractor.release()
            }
        }
        return -1
    }

    /** Concatenates the video tracks of [inputs], offsetting timestamps. */
    private fun writeVideoSamples(muxer: MediaMuxer, trackIndex: Int, inputs: List<File>) {
        var offsetUs = 0L
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
                var firstSampleTimeUs = -1L
                var lastSampleTimeUs = -1L
                var sampleCount = 0L
                val buffer = ByteBuffer.allocate(256 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTime
                    lastSampleTimeUs = sampleTime
                    sampleCount++
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = sampleTime - firstSampleTimeUs.coerceAtLeast(0L) + offsetUs
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                offsetUs += if (lastSampleTimeUs >= 0 && firstSampleTimeUs >= 0) {
                    concatSegmentDurationUs(firstSampleTimeUs, lastSampleTimeUs, sampleCount)
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(input.path)
                        (retriever.extractMetadata(
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
    }

    /**
     * Segment duration from first/last PTS plus one mean frame interval.
     * Segments each start their own PTS timeline (often near 0 with
     * overlapping ranges), so summing raw last-sample times double-counts;
     * re-basing on (last − first + delta) keeps the concat monotonic.
     */
    internal fun concatSegmentDurationUs(firstUs: Long, lastUs: Long, sampleCount: Long): Long {
        if (lastUs < firstUs || sampleCount <= 0) return 0L
        val delta = if (sampleCount > 1) (lastUs - firstUs) / (sampleCount - 1) else 0L
        return (lastUs - firstUs) + delta.coerceAtLeast(0L)
    }

    /** AAC-LC encoded frames for a mono s16le [pcm] slice, PTS from 0. */
    private fun encodeAac(pcm: ByteArray): AacFrames {        val frameBytes = AUDIO_FRAME_SAMPLES * 2
        val rate = AUDIO_SAMPLE_RATE
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var csd0: ByteBuffer? = null
        val frames = ArrayList<Pair<ByteBuffer, Long>>()
        val info = MediaCodec.BufferInfo()
        val pcmBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val pad = ByteArray(frameBytes)
        var ptsFrame = 0L
        var feedIdx = 0
        val totalFrames = (pcm.size + frameBytes - 1) / frameBytes
        try {
            fun drain() {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIndex < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val asc = ByteBuffer.allocate(info.size)
                        asc.put(buf)
                        asc.flip()
                        csd0 = asc
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIndex, false)
                        return
                    }
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frame = ByteBuffer.allocate(info.size)
                        frame.put(buf)
                        frame.flip()
                        frames.add(frame to (ptsFrame * 1_000_000L * AUDIO_FRAME_SAMPLES / rate))
                        ptsFrame++
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            // Feed all frames with a bounded input timeout, draining
            // opportunistically; the encoder pipelines asynchronously. The old
            // dequeueInputBuffer(0) busy-spun when the codec was saturated.
            var starveSpins = 0
            while (feedIdx < totalFrames) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    starveSpins = 0
                    val input = codec.getInputBuffer(inputIndex)!!
                    input.clear()
                    val start = feedIdx * frameBytes
                    val len = minOf(frameBytes, pcm.size - start)
                    val src = pcmBuf.duplicate()
                    src.position(start)
                    src.limit(start + len)
                    input.put(src)
                    if (len < frameBytes) input.put(pad, 0, frameBytes - len)
                    codec.queueInputBuffer(inputIndex, 0, frameBytes, 0L, 0)
                    feedIdx++
                } else {
                    // Codec saturated: back off briefly instead of spinning.
                    if (++starveSpins % 50 == 0) {
                        Log.w(TAG, "AAC encoder input starved ($starveSpins spins)")
                    }
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("AAC encode interrupted")
                    }
                }
                drain()
            }
            var eosQueued = false
            var eosSpins = 0
            while (!eosQueued) {
                val eosIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (eosIndex >= 0) {
                    codec.queueInputBuffer(
                        eosIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                } else if (++eosSpins > MAX_EOS_SPINS) {
                    throw IllegalStateException("AAC encoder never freed an input for EOS")
                }
                drain()
            }
            drain()
            if (csd0 == null) throw IllegalStateException("AAC codec produced no config")
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
        return AacFrames(csd0!!, frames)
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
                        Environment.DIRECTORY_MOVIES + "/level2"
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
                    "level2"
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

    /** Read stream for a stored clip (MediaStore or app-private fallback). */
    fun openStream(name: String): java.io.InputStream? {
        val appContext = context ?: return null
        val uri = queryUriByName(name)
        if (uri != null) {
            try {
                return appContext.contentResolver.openInputStream(uri)
            } catch (_: Exception) {
            }
        }
        val fallback = File(appContext.filesDir, "videos/$name")
        return if (fallback.exists()) fallback.inputStream() else null
    }

    /** Whether the stored clip carries an audio track. Pure read, no FGS. */
    fun hasAudio(name: String): Boolean {
        val appContext = context ?: return false
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        return try {
            val extractor = MediaExtractor()
            try {
                if (uri != null) {
                    extractor.setDataSource(appContext, uri, null)
                } else if (fallback.exists()) {
                    extractor.setDataSource(fallback.path)
                } else {
                    return false
                }
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        return true
                    }
                }
                false
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasAudio failed for $name", e)
            false
        }
    }

    /**
     * Dimensions of the stored clip (width x height), or null when the clip is
     * missing or its headers can't be read. Pure read — works without the FGS.
     */
    fun videoInfo(name: String): Map<String, Int>? {
        val appContext = context ?: return null
        val uri = queryUriByName(name)
        val fallback = File(appContext.filesDir, "videos/$name")
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri != null) {
                retriever.setDataSource(appContext, uri)
            } else if (fallback.exists()) {
                retriever.setDataSource(fallback.path)
            } else {
                return null
            }
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return null
            mapOf("width" to width, "height" to height)
        } catch (e: Exception) {
            Log.w(TAG, "videoInfo failed for $name", e)
            null
        } finally {
            retriever.release()
        }
    }

    /** Returns an error message on failure, or null when the player opened. */
    fun open(name: String): String? {
        val appContext = context ?: return "no application context"
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
            return "no such clip: $name"
        }
        // Launched from the application context, so NEW_TASK is mandatory.
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "open video failed for $name", e)
            "no app can play this clip: ${e.message}"
        }
    }
}

/** AAC-LC encoded audio track: decoder config plus (buffer, PTS) frames. */
private class AacFrames(
    val csd0: ByteBuffer,
    val frames: List<Pair<ByteBuffer, Long>>,
)

/**
 * Bounded rolling buffer of mono s16le mic PCM. Chunks arrive on the mic read
 * thread with their absolute start sample; the oldest chunk outside the rolling
 * window is dropped. [slice] returns a zero-filled contiguous window covering
 * [startSample, endSample), synthesizing silence where no mic data exists.
 */
private class AudioPcmBuffer(
    val sampleRate: Int,
    private val windowSamples: Long,
) {
    private class Chunk(val pcm: ByteArray, val startSample: Long)

    private val lock = Object()
    private val chunks = ArrayList<Chunk>()
    private var lastSampleInternal = 0L

    val lastSample: Long
        get() = synchronized(lock) { lastSampleInternal }

    val hasData: Boolean
        get() = synchronized(lock) { chunks.isNotEmpty() }

    fun add(pcm: ByteArray, startSample: Long) {
        val endSample = startSample + pcm.size / 2
        synchronized(lock) {
            chunks.add(Chunk(pcm, startSample))
            if (lastSampleInternal < endSample) lastSampleInternal = endSample
            val minStart = endSample - windowSamples
            while (chunks.isNotEmpty() && chunks[0].startSample < minStart) {
                chunks.removeAt(0)
            }
        }
    }

    fun slice(startSample: Long, endSample: Long): ByteArray {
        if (endSample <= startSample) return ByteArray(0)
        val lengthSamples = (endSample - startSample).coerceIn(0L, Int.MAX_VALUE / 2L)
        val out = ByteArray((lengthSamples * 2L).toInt())
        synchronized(lock) {
            for (chunk in chunks) {
                if (chunk.startSample >= endSample) break
                val chunkEnd = chunk.startSample + chunk.pcm.size / 2
                if (chunkEnd <= startSample) continue
                val from = maxOf(chunk.startSample, startSample)
                val to = minOf(chunkEnd, endSample)
                val byteOffset = ((from - chunk.startSample).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                    .coerceIn(0, chunk.pcm.size)
                val byteLen = ((to - from).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                    .coerceIn(0, chunk.pcm.size - byteOffset)
                val outByteOffset = ((from - startSample).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                if (outByteOffset + byteLen > out.size) continue
                System.arraycopy(
                    chunk.pcm, byteOffset,
                    out, outByteOffset, byteLen,
                )
            }
        }
        return out
    }

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            lastSampleInternal = 0L
        }
    }
}
