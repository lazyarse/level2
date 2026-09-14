package io.securitycam.level2.monitor

import android.content.Context
import io.securitycam.level2.camera_service.CameraEvents
import io.securitycam.level2.camera_service.CameraFrameBus
import io.securitycam.level2.camera_service.MonitoringServiceController
import io.securitycam.level2.camera_service.VideoClipRecorder
import io.securitycam.level2.channels.ChannelRegistry
import io.securitycam.level2.backup.RemoteKeys
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.DetectorType
import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.mediaFileName
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.AudioWindow
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.HealthEpisode
import io.securitycam.level2.detection.HealthWatchdog
import io.securitycam.level2.core.TriggerEvent
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.DetectorRegistry
import io.securitycam.level2.detection.audio.AudioClassifierFactory
import io.securitycam.level2.detection.audio.AudioEventClassifier
import io.securitycam.level2.detection.audio.MockAudioEventClassifier
import io.securitycam.level2.detection.face.FaceDetector
import io.securitycam.level2.detection.face.FaceEmbedder
import io.securitycam.level2.detection.face.FaceEmbeddingEngine
import io.securitycam.level2.detection.face.FaceEngine
import io.securitycam.level2.detection.face.FaceRecognizer
import io.securitycam.level2.identity.KnownFaceStore
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.detection.pipeline.AnalysisDispatcher
import io.securitycam.level2.detection.pipeline.DetectorPipeline
import io.securitycam.level2.event.EventPipeline
import io.securitycam.level2.event.TriggerBatch
import io.securitycam.level2.event.TriggerBatcher
import io.securitycam.level2.storage.AppDatabase
import io.securitycam.level2.storage.FileSnapshotStore
import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import io.securitycam.level2.storage.OutboxStore
import io.securitycam.level2.storage.RoomEventLog
import io.securitycam.level2.sensors.PcmWindowAccumulator
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Runtime-owned scope: a SupervisorJob child of the creator's scope, so a
     * failure in one collector never cancels the ViewModel scope, and stop()
     * cancels all runtime work in one place.
     */
    private val runtimeScope: CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
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

    /** Set of trigger types that have fired since monitoring started. */
    private val _activeTriggerTypes = MutableStateFlow<Set<String>>(emptySet())
    val activeTriggerTypes: StateFlow<Set<String>> = _activeTriggerTypes.asStateFlow()

    /**
     * Edge signal: one emission per trigger occurrence. UI pulses (status-bar
     * icons) must consume this — an accumulating StateFlow<Set> conflates
     * identical consecutive sets, so a second motion after the icon timeout
     * would never re-appear.
     */
    private val _triggerEvents = MutableSharedFlow<TriggerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents.asSharedFlow()

    @Volatile
    private var stopped = false

    /** Fast-notify: batch timestamp → eventId for late video linking. */
    private val pendingVideoEventIds = java.util.concurrent.ConcurrentHashMap<Instant, Long>()

    /**
     * Runtime-scoped detector factories (Wave 4): this runtime's face
     * override lives here, never on the process-global [DetectorRegistry].
     */
    private lateinit var scopedRegistry: DetectorRegistry

    /** This runtime's detector registry (test seam: proves scoping). */
    val detectorRegistry: DetectorRegistry get() = scopedRegistry

    /**
     * Face roster snapshot taken at creation (Wave 4): later
     * [io.securitycam.level2.identity.FaceDirectory] updates do not move a
     * live session; a restart picks them up. Cleared on [stop].
     */
    var faceRoster: List<KnownFace> = emptyList()
        private set

    companion object {
        private const val TAG = "MonitoringRuntime"
        private const val HEALTH_ID = "health"

        /** Upper bound for a still capture before the batch proceeds without it. */
        private const val CAPTURE_TIMEOUT_MS = 15_000L

        /**
         * Hard cap on a continuous wave's batch/clip (see TriggerBatcher's
         * maxBatchDuration): perpetual motion (fan, swaying trees, screen
         * glare) can't produce a single never-ending event.
         */
        private val MAX_BATCH_DURATION: Duration = Duration.ofSeconds(120)

        /** mediaPath prefix marking a MediaStore clip display name. */
        const val CLIP_MEDIA_PREFIX = "clip:"

        suspend fun create(
            context: Context,
            settings: AppSettings,
            scope: CoroutineScope,
            healthCheckInterval: Duration = Duration.ofSeconds(5),
            faceStoreFactory: (Context) -> KnownFaceStore = { KnownFaceStore(it) },
            embedderLoader: suspend (Context) -> FaceEmbedder? = { ctx ->
                withContext(Dispatchers.IO) { FaceEmbeddingEngine.load(ctx) }
            },
            classifierLoader: suspend (Context) -> AudioEventClassifier = { ctx ->
                AudioClassifierFactory.build(ctx)
            },
            /**
             * Face detection engine override (tests inject fakes here; null
             * keeps the shipped MediaPipe engine via the detector default).
             */
            faceEngineFactory: (() -> FaceEngine)? = null,
        ): MonitoringRuntime {
            val appContext = context.applicationContext
            val runtime = MonitoringRuntime(appContext, settings, scope)
            // While recognition is enabled the face factory builds the
            // recognizing variant; registering unconditionally keeps repeated
            // create() calls consistent with the current settings.
            val recognitionOn = AppSettings.faceRecognitionEnabled(settings)
            val faceStore = if (recognitionOn) faceStoreFactory(appContext) else null
            // Native/model IO stays off the main thread.
            val embedder = if (recognitionOn) {
                embedderLoader(appContext)
            } else {
                null
            }
            // Wave 4: snapshot the live roster instead of publishing into the
            // process-global FaceDirectory — overlapping runtimes (monitoring
            // + face-enrollment capture) keep independent rosters. Later
            // enrollments/deletes take effect on restart.
            runtime.faceRoster = if (recognitionOn) settings.knownFaces.toList() else emptyList()
            val matchThreshold = if (recognitionOn) {
                settings.detectorConfigs[TriggerType.faceKnown]?.threshold
                    ?: AppSettings.FACE_MATCH_THRESHOLD
            } else {
                0.0
            }
            // Wave 4: the face override is registered on a runtime-scoped
            // registry passed explicitly to the pipeline — the global
            // DetectorRegistry is never mutated here.
            val scoped = DetectorRegistry.withDefaults()
            val faceEngine = faceEngineFactory?.invoke()
            scoped.register(TriggerType.face) { c ->
                if (recognitionOn) {
                    FaceRecognizer(
                        c,
                        faceStore!!,
                        embedder,
                        { runtime.faceRoster },
                        matchThreshold,
                        engine = faceEngine,
                    )
                } else {
                    FaceDetector(c, engine = faceEngine)
                }
            }
            runtime.scopedRegistry = scoped
            // Skip the YAMNet model load when no enabled detector consumes
            // audio scores: the classifier would run on every window for
            // nobody. The mic itself stays up (clip audio + watchdog
            // liveness); only the model inference is gated.
            val anyAudioAnalyzer = settings.detectorConfigs.values.any { cfg ->
                cfg.enabled &&
                    DetectorType.fromKey(cfg.type)?.consumesAudio == true
            }
            runtime.pipeline = DetectorPipeline(
                classifier = if (anyAudioAnalyzer) {
                    classifierLoader(appContext)
                } else {
                    MockAudioEventClassifier()
                },
                configs = settings.detectorConfigs.values.toList(),
                registry = scoped,
            )
            withContext(Dispatchers.IO) { runtime.pipeline.init() }
            runtime.pipeline.setZones(settings.detectionZones, settings.exclusionZones)
            runtime.pipeline.setTripwireZones(settings.tripwireZones)
            val roomLog = RoomEventLog(AppDatabase.get(appContext).eventDao())
            runtime.eventPipeline = EventPipeline(
                cameraName = settings.cameraName,
                detectorConfigs = settings.detectorConfigs,
                channelConfigs = settings.channelConfigs.associateBy { it.id },
                recorder = roomLog,
                snapshotStore = FileSnapshotStore(
                    File(appContext.filesDir, "snapshots").absolutePath,
                ),
                channelFactories = ChannelRegistry.factories,
                // Offline queue: exhausted deliveries land in the Room outbox
                // and are drained by OutboxWorker when connectivity returns.
                outboxSink = { row ->
                    OutboxStore.from(AppDatabase.get(appContext)).enqueue(row)
                },
            )
            runtime.batcher = TriggerBatcher(
                scope = runtime.runtimeScope,
                window = settings.notificationMergeWindow,
                captureSnapshot = { runtime.captureSnapshot() },
                captureVideo = { triggerAt -> runtime.captureVideo(triggerAt) },
                onTriggerExtended = { VideoClipRecorder.extendExport() },
                onBatchClose = { VideoClipRecorder.endExport() },
                maxBatchDuration = MAX_BATCH_DURATION,
                onVideoReady = { batchTime, videoName ->
                    runtime.handleVideoReady(batchTime, videoName)
                },
            )
            runtime.frameDispatcher =
                AnalysisDispatcher<AnalysisFrame>(runtime.runtimeScope, process = { runtime.pipeline.processFrame(it) })
            runtime.audioDispatcher =
                AnalysisDispatcher<AudioWindow>(runtime.runtimeScope, process = { runtime.pipeline.processAudio(it) })
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
        triggerJob = runtimeScope.launch {
            pipeline.triggers.collect {
                // Per-trigger guard: one bad trigger (batcher/collector bug)
                // must not kill the collector for all future events.
                runCatching {
                    android.util.Log.d(TAG, "trigger type=${it.triggerType} score=${it.score}")
                    _activeTriggerTypes.value = _activeTriggerTypes.value + it.triggerType
                    _triggerEvents.tryEmit(it)
                    batcher.add(it)
                }.onFailure { t ->
                    android.util.Log.w(TAG, "trigger collect failed", t)
                }
            }
        }
        batchJob = runtimeScope.launch {
            batcher.batches.collect {
                // Per-batch guard: one failing batch (channel crash, IO) must
                // not end event recording for the rest of the session.
                runCatching {
                    android.util.Log.d(TAG, "batch emitted triggers=${it.triggers.size}")
                    val eventId = eventPipeline.handleBatch(it)
                    pendingVideoEventIds[it.timestamp] = eventId
                    android.util.Log.d(TAG, "event recorded id=$eventId type=${it.triggers.firstOrNull()?.triggerType} video=${it.videoName}")
                    queueCloudBackups(it)
                }.onFailure { t ->
                    android.util.Log.w(TAG, "batch handling failed", t)
                }
            }
        }
        watchdog?.let { watchdog ->
            healthJob = runtimeScope.launch {
                while (isActive) {
                    delay(healthCheckInterval.toMillis())
                    runCatching { watchdog.check(Instant.now()) }
                }
            }
        }
    }

    suspend fun stop() {
        if (stopped) return
        stopped = true
        _activeTriggerTypes.value = emptySet()
        runCatching { CameraFrameBus.remove(frameListener) }
        runCatching { CameraEvents.removeMicPcmListener(micListener) }
        // A wave interrupted mid-window must still record its event (and link
        // its clip once the in-flight export resolves), so drain the open
        // batch into the collector BEFORE cancelling it / disposing the
        // batcher — which would otherwise cancel the captures and lose both
        // the row and the video link.
        runCatching { triggerJob?.cancel() }
        triggerJob = null
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                batcher.drainIfCurrent()?.let { emitForStop(it) }
            }
        }
        runCatching { batchJob?.cancel() }
        batchJob = null
        runCatching { healthJob?.cancel() }
        healthJob = null
        // One throwing dispose must not skip the rest.
        runCatching { batcher.dispose() }
        runCatching { frameDispatcher.dispose() }
        runCatching { audioDispatcher.dispose() }
        runCatching { pipeline.dispose() }
        // Wave 4: drop this runtime's face override and roster snapshot so a
        // stopped session retains no detection state.
        runCatching { if (::scopedRegistry.isInitialized) scopedRegistry.unregister(TriggerType.face) }
        faceRoster = emptyList()
        runCatching { runtimeScope.coroutineContext[Job]?.cancel() }
    }

    /** Handles a batch drained on stop just like the collector does. */
    private suspend fun emitForStop(batch: TriggerBatch) {
        runCatching {
            android.util.Log.d(TAG, "batch emitted (stop) triggers=${batch.triggers.size}")
            val eventId = eventPipeline.handleBatch(batch)
            pendingVideoEventIds[batch.timestamp] = eventId
            android.util.Log.d(
                TAG,
                "event recorded (stop) id=$eventId type=${batch.triggers.firstOrNull()?.triggerType} video=${batch.videoName}",
            )
            queueCloudBackups(batch)
        }.onFailure { t ->
            android.util.Log.w(TAG, "stop batch handling failed", t)
        }
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

    private suspend fun captureSnapshot(): Snapshot? {
        var callback: MonitoringServiceController.StillCallback? = null
        try {
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    callback = object : MonitoringServiceController.StillCallback {
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
                    }
                    MonitoringServiceController.captureStill(callback!!)
                    cont.invokeOnCancellation {
                        android.util.Log.w(TAG, "captureSnapshot cancelled/timed out")
                    }
                }
            }
        } finally {
            callback = null
        }
    }

    private suspend fun captureVideo(triggerAt: Instant): String? {
        // A continuous wave can hold the batch open for up to MAX_BATCH_DURATION
        // plus its post-roll tail and the (possible staring) mux; budget well
        // past that so a long clip is never cut short by the timeout.
        val budget = MAX_BATCH_DURATION.toMillis() +
            (settings.preRollSeconds + settings.postRollSeconds) * 1000L +
            60_000L
        val startMs = System.currentTimeMillis()
        android.util.Log.i(TAG, "captureVideo start triggerAt=$triggerAt budget=${budget}ms")
        val result = withTimeoutOrNull(budget) {
            suspendCancellableCoroutine { cont ->
                VideoClipRecorder.exportClip(
                    triggerAtMs = triggerAt.toEpochMilli(),
                    preRollSeconds = settings.preRollSeconds,
                    postRollSeconds = settings.postRollSeconds,
                    camName = settings.cameraName,
                ) { name ->
                    if (cont.isActive) cont.resumeWith(Result.success(name))
                }
                cont.invokeOnCancellation {
                    android.util.Log.w(TAG, "captureVideo cancelled/timed out")
                }
            }
        }
        val elapsed = System.currentTimeMillis() - startMs
        if (result == null) {
            android.util.Log.w(TAG, "captureVideo done triggerAt=$triggerAt elapsed=${elapsed}ms result=null (timeout or export failed, budget=${budget}ms)")
        } else {
            android.util.Log.i(TAG, "captureVideo done triggerAt=$triggerAt elapsed=${elapsed}ms video=$result")
        }
        return result
    }

    /**
     * Cloud backup (see docs/plans/2026-08-24-cloud-backup-design.md): after
     * the event is recorded, queue snapshot/clip uploads through the shared
     * outbox. Rows carry a media *reference* (snapshot file name, or the
     * "clip:" prefixed MediaStore display name) — bytes are opened at send.
     */
    private suspend fun queueCloudBackups(batch: TriggerBatch) {
        val cb = settings.cloudBackup
        if (!cb.enabled) return
        val store = OutboxStore.from(AppDatabase.get(context))
        val now = Instant.now().toEpochMilli()
        batch.snapshot?.let { snap ->
            if (!cb.backupSnapshots) return@let
            store.enqueue(
                OutboxEntity(
                    createdAt = now,
                    kind = OutboxKind.BACKUP,
                    mediaPath = snap.name,
                    remotePath = RemoteKeys.forMedia(settings.cameraName, snap.name, now),
                ),
            )
        }
        batch.videoName?.let { video ->
            if (!cb.backupClips) return@let
            store.enqueue(
                OutboxEntity(
                    createdAt = now,
                    kind = OutboxKind.BACKUP,
                    mediaPath = "$CLIP_MEDIA_PREFIX$video",
                    remotePath = RemoteKeys.forMedia(settings.cameraName, video, now),
                ),
            )
        }
    }

    private suspend fun handleVideoReady(batchTime: Instant, videoName: String?) {
        val eventId = pendingVideoEventIds.remove(batchTime)
        if (eventId == null) {
            android.util.Log.w(TAG, "video ready but no eventId for batchTime=$batchTime video=$videoName")
            return
        }
        if (videoName == null) {
            android.util.Log.i(TAG, "video ready null for eventId=$eventId batchTime=$batchTime (no clip)")
            return
        }
        try {
            val log = RoomEventLog(AppDatabase.get(context).eventDao())
            log.updateVideoName(eventId, videoName)
            android.util.Log.i(TAG, "video linked eventId=$eventId batchTime=$batchTime video=$videoName")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "video link failed eventId=$eventId video=$videoName", e)
            return
        }
        // Cloud backup for the late clip.
        val cb = settings.cloudBackup
        if (!cb.enabled || !cb.backupClips) return
        try {
            val store = OutboxStore.from(AppDatabase.get(context))
            val now = Instant.now().toEpochMilli()
            store.enqueue(
                OutboxEntity(
                    createdAt = now,
                    kind = OutboxKind.BACKUP,
                    mediaPath = "$CLIP_MEDIA_PREFIX$videoName",
                    remotePath = RemoteKeys.forMedia(settings.cameraName, videoName, now),
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "video backup enqueue failed video=$videoName", e)
        }
    }
}