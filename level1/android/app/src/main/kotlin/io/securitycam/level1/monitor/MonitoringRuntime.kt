package io.securitycam.level1.monitor

import android.content.Context
import io.securitycam.level1.camera_service.CameraEvents
import io.securitycam.level1.camera_service.CameraFrameBus
import io.securitycam.level1.camera_service.MonitoringServiceController
import io.securitycam.level1.camera_service.VideoClipRecorder
import io.securitycam.level1.channels.ChannelRegistry
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.mediaFileName
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.AudioWindow
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.HealthEpisode
import io.securitycam.level1.detection.HealthWatchdog
import io.securitycam.level1.core.TriggerEvent
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.DetectorRegistry
import io.securitycam.level1.detection.audio.AudioClassifierFactory
import io.securitycam.level1.detection.face.FaceDetector
import io.securitycam.level1.detection.face.FaceEmbeddingEngine
import io.securitycam.level1.detection.face.FaceRecognizer
import io.securitycam.level1.identity.KnownFaceStore
import io.securitycam.level1.detection.pipeline.AnalysisDispatcher
import io.securitycam.level1.detection.pipeline.DetectorPipeline
import io.securitycam.level1.event.EventPipeline
import io.securitycam.level1.event.TriggerBatcher
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RoomEventLog
import io.securitycam.level1.sensors.PcmWindowAccumulator
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the full native detection→event path while monitoring runs (port of the
 * Dart `MonitorController.start()` runtime wiring):
 *
 * CameraFrameBus BGR frames ─┐
 * MicCapture PCM windows ────┴─→ AnalysisDispatcher(s) → DetectorPipeline →
 * TriggerBatcher (snapshot + clip capture) → EventPipeline (channels + Room).
 */
class MonitoringRuntime private constructor(
    private val context: Context,
    val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private lateinit var pipeline: DetectorPipeline
    private lateinit var batcher: TriggerBatcher
    private lateinit var eventPipeline: EventPipeline
    private lateinit var frameDispatcher: AnalysisDispatcher<AnalysisFrame>
    private lateinit var audioDispatcher: AnalysisDispatcher<AudioWindow>
    private var triggerJob: Job? = null
    private var batchJob: Job? = null
    private var healthJob: Job? = null
    private val pcmAccumulator = PcmWindowAccumulator()

    /** True while a feed-stall health episode is active (drives the UI banner). */
    private val _healthStalled = MutableStateFlow(false)
    val healthStalled: StateFlow<Boolean> = _healthStalled.asStateFlow()

    @Volatile
    private var stopped = false

    companion object {
        private const val TAG = "MonitoringRuntime"
        private const val HEALTH_ID = "health"

        suspend fun create(
            context: Context,
            settings: AppSettings,
            scope: CoroutineScope,
            healthCheckInterval: Duration = Duration.ofSeconds(5),
        ): MonitoringRuntime {
            val appContext = context.applicationContext
            val runtime = MonitoringRuntime(appContext, settings, scope)
            // While recognition is enabled the face factory builds the
            // recognizing variant; registering unconditionally keeps repeated
            // create() calls consistent with the current settings.
            val recognitionOn = AppSettings.faceRecognitionEnabled(settings)
            val faceStore = if (recognitionOn) KnownFaceStore(appContext) else null
            val embedder = if (recognitionOn) FaceEmbeddingEngine.load(appContext) else null
            val matchThreshold = if (recognitionOn) {
                settings.detectorConfigs[TriggerType.faceKnown]?.threshold
                    ?: AppSettings.FACE_MATCH_THRESHOLD
            } else {
                0.0
            }
            DetectorRegistry.register(TriggerType.face) { c ->
                if (recognitionOn) {
                    FaceRecognizer(c, faceStore!!, embedder, { settings.knownFaces }, matchThreshold)
                } else {
                    FaceDetector(c)
                }
            }
            runtime.pipeline = DetectorPipeline(
                classifier = AudioClassifierFactory.build(appContext),
                configs = settings.detectorConfigs.values.toList(),
            )
            runtime.pipeline.init()
            runtime.pipeline.setRegions(settings.detectionRegions, settings.exclusionRegions)
            runtime.eventPipeline = EventPipeline(
                cameraName = settings.cameraName,
                detectorConfigs = settings.detectorConfigs,
                channelConfigs = settings.channelConfigs.associateBy { it.id },
                recorder = RoomEventLog(AppDatabase.get(appContext).eventDao()),
                snapshotStore = FileSnapshotStore(
                    File(appContext.filesDir, "snapshots").absolutePath,
                ),
                channelFactories = ChannelRegistry.factories,
            )
            runtime.batcher = TriggerBatcher(
                scope = scope,
                window = settings.notificationMergeWindow,
                captureSnapshot = { runtime.captureSnapshot() },
                captureVideo = { triggerAt -> runtime.captureVideo(triggerAt) },
            )
            runtime.frameDispatcher =
                AnalysisDispatcher<AnalysisFrame>(scope, process = { runtime.pipeline.processFrame(it) })
            runtime.audioDispatcher =
                AnalysisDispatcher<AudioWindow>(scope, process = { runtime.pipeline.processAudio(it) })
            val healthConfig = settings.detectorConfigs[TriggerType.health]
            if (healthConfig?.enabled != false) {
                runtime.watchdog = HealthWatchdog(
                    onEpisode = { episode -> runtime.onHealthEpisode(episode) },
                )
            }
            runtime.healthCheckInterval = healthCheckInterval
            return runtime
        }
    }

    private lateinit var healthCheckInterval: Duration
    private var watchdog: HealthWatchdog? = null

    /** Emits stall/recovery as a first-class health trigger through the pipeline. */
    private fun onHealthEpisode(episode: HealthEpisode) {
        _healthStalled.value = !episode.recovered
        val detail = if (episode.recovered) {
            HealthWatchdog.DETAIL_RECOVERED
        } else {
            HealthWatchdog.DETAIL_STALL
        }
        android.util.Log.w(TAG, "health episode recovered=${episode.recovered}")
        pipeline.emitTrigger(
            id = HEALTH_ID,
            cooldown = Duration.ofSeconds(30),
            event = TriggerEvent(
                timestamp = Instant.now(),
                triggerType = TriggerType.health,
                score = 1.0,
                detectorId = HEALTH_ID,
                detail = detail,
            ),
        )
    }

    /** Subscribes to camera/mic buses and starts the trigger/batch collectors. */
    fun begin() {
        CameraFrameBus.add(frameListener)
        CameraEvents.addMicPcmListener(micListener)
        triggerJob = scope.launch {
            pipeline.triggers.collect {
                android.util.Log.i(TAG, "trigger type=${it.triggerType} score=${it.score}")
                batcher.add(it)
            }
        }
        batchJob = scope.launch {
            batcher.batches.collect {
                android.util.Log.i(TAG, "batch emitted triggers=${it.triggers.size}")
                eventPipeline.handleBatch(it)
                android.util.Log.i(TAG, "event recorded type=${it.triggers.firstOrNull()?.triggerType} video=${it.videoName}")
            }
        }
        watchdog?.let { watchdog ->
            healthJob = scope.launch {
                while (isActive) {
                    delay(healthCheckInterval.toMillis())
                    watchdog.check(Instant.now())
                }
            }
        }
    }

    suspend fun stop() {
        if (stopped) return
        stopped = true
        CameraFrameBus.remove(frameListener)
        CameraEvents.removeMicPcmListener(micListener)
        triggerJob?.cancel()
        triggerJob = null
        batchJob?.cancel()
        batchJob = null
        healthJob?.cancel()
        healthJob = null
        batcher.dispose()
        frameDispatcher.dispose()
        audioDispatcher.dispose()
        pipeline.dispose()
    }

    private val frameListener: (bgr: ByteArray, width: Int, height: Int) -> Unit =
        { bgr, width, height ->
            if (!stopped) {
                val now = Instant.now()
                watchdog?.noteFrame(now)
                val color = ColorBitmap(width, height, bgr)
                frameDispatcher.add(
                    AnalysisFrame(
                        timestamp = now,
                        bitmap = color.toGrayscale(),
                        color = color,
                    ),
                )
            }
        }

    private val micListener: (pcm: ByteArray, startSample: Long) -> Unit = { pcm, _ ->
        if (!stopped) {
            for (window in pcmAccumulator.add(pcm)) {
                watchdog?.noteAudio(Instant.now())
                audioDispatcher.add(window)
            }
        }
    }

    private suspend fun captureSnapshot(): Snapshot? =
        suspendCancellableCoroutine { cont ->
            MonitoringServiceController.captureStill(object : MonitoringServiceController.StillCallback {
                override fun onResult(bytes: ByteArray) {
                    if (!cont.isActive) return
                    val name = mediaFileName(
                        timestamp = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()),
                        cameraName = settings.cameraName,
                        extension = "jpg",
                    )
                    cont.resumeWith(Result.success(Snapshot(bytes, "image/jpeg", name)))
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            })
        }

    private suspend fun captureVideo(triggerAt: Instant): String? =
        suspendCancellableCoroutine { cont ->
            VideoClipRecorder.exportClip(
                triggerAtMs = triggerAt.toEpochMilli(),
                preRollSeconds = settings.preRollSeconds,
                postRollSeconds = settings.postRollSeconds,
                camName = settings.cameraName,
            ) { name ->
                if (cont.isActive) cont.resumeWith(Result.success(name))
            }
        }
}