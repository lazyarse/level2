package io.securitycam.level2.monitor

import android.content.Context
import io.securitycam.level2.BuildConfig
import io.securitycam.level2.camera_service.CameraEvents
import io.securitycam.level2.camera_service.CameraFrameBus
import io.securitycam.level2.camera_service.MonitoringServiceController
import io.securitycam.level2.camera_service.VideoClipRecorder
import io.securitycam.level2.channels.AlertLog
import io.securitycam.level2.channels.AlertLogEntry
import io.securitycam.level2.channels.ChannelRegistry
import io.securitycam.level2.backup.RemoteKeys
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.DetectorType
import io.securitycam.level2.core.DetectionSpeed
import io.securitycam.level2.core.isDue
import io.securitycam.level2.core.supportsVideoPreview
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
import io.securitycam.level2.event.RecordedEvent
import io.securitycam.level2.event.TriggerBatch
import io.securitycam.level2.event.TriggerBatcher
import io.securitycam.level2.storage.AppDatabase
import io.securitycam.level2.storage.FileSnapshotStore
import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import io.securitycam.level2.storage.OutboxStore
import io.securitycam.level2.storage.RoomEventLog
import io.securitycam.level2.sensors.PcmWindowAccumulator
import io.securitycam.level2.media.Mp4PreviewGenerator
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the full native detection→event path while monitoring runs:
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
    private lateinit var roomLog: RoomEventLog

    /**
     * Per-trigger fast path: every trigger sends its channels immediately
     * (fresh still, no merge-window wait) while the batcher still merges the
     * wave into ONE early DB row, updated as the wave continues. Keyed by the
     * wave's openedAt — the same key the batch flow and VideoLinkRegistry use.
     */
    private data class WaveState(
        val triggers: MutableList<TriggerEvent> = mutableListOf(),
        var eventId: Long = -1L,
        var snapshotName: String? = null,
        val statuses: LinkedHashMap<String, String> = LinkedHashMap(),
        var finalized: Boolean = false,
        /** Last per-channel send start (epoch ms): the frequency gate. */
        val lastSentMs: MutableMap<String, Long> = mutableMapOf(),
    )
    private val waveMutex = Mutex()
    private val waves = HashMap<Instant, WaveState>()
    private lateinit var frameDispatcher: AnalysisDispatcher<AnalysisFrame>
    private lateinit var audioDispatcher: AnalysisDispatcher<AudioWindow>
    private var triggerJob: Job? = null
    private var batchJob: Job? = null
    private var healthJob: Job? = null
    private var slowCheckJob: Job? = null
    private val pcmAccumulator = PcmWindowAccumulator()

    /** True while a feed-stall health episode is active (drives the UI banner). */
    private val _healthStalled = MutableStateFlow(false)
    val healthStalled: StateFlow<Boolean> = _healthStalled.asStateFlow()

    /** True once sampled gated passes prove this phone is slow (drives the UI nudge). */
    private val _slowDevice = MutableStateFlow(false)
    val slowDevice: StateFlow<Boolean> = _slowDevice.asStateFlow()

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

    /**
     * Runtime-scoped detector factories (Wave 4): this runtime's face
     * override lives here, never on the process-global [DetectorRegistry].
     */
    private lateinit var scopedRegistry: DetectorRegistry

    /** This runtime's detector registry (test seam: proves scoping). */
    val detectorRegistry: DetectorRegistry get() = scopedRegistry

    /**
     * Face roster snapshot taken at creation: later enrollments do not move a
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

        /**
         * Slow-device nudge: a gated pass taking at least this long proves
         * the phone will stutter the preview during motion (fast phones run
         * the same pass in ~50–150 ms; weak ones take 600+ ms).
         */
        const val SLOW_GATED_PASS_MS = 400L

        /** How many gated passes to sample before deciding slow/not-slow. */
        const val SLOW_GATED_SAMPLES = 3

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
            // The fdroid flavor excludes the embedding weights entirely, so
            // recognition is forced off there even for restored full-build
            // settings: FaceRecognizer would fall back to plain-face anyway,
            // but never registering it keeps rosters/stores out of memory.
            val recognitionOn = AppSettings.faceRecognitionEnabled(settings) &&
                BuildConfig.FACE_RECOGNITION_SUPPORTED
            val faceStore = if (recognitionOn) faceStoreFactory(appContext) else null
            // Native/model IO stays off the main thread.
            val embedder = if (recognitionOn) {
                embedderLoader(appContext)
            } else {
                null
            }
            // Snapshot the live roster — overlapping runtimes (monitoring
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
                // User's detection-speed tier paces the heavy gated ML pass;
                // takes effect on (re)start.
                gatedMinInterval = DetectionSpeed.gatedInterval(settings.detectionSpeed),
            )
            withContext(Dispatchers.IO) { runtime.pipeline.init() }
            runtime.pipeline.setZones(settings.detectionZones, settings.exclusionZones)
            runtime.pipeline.setTripwireZones(settings.tripwireZones)
            val roomLog = RoomEventLog(AppDatabase.get(appContext).eventDao())
            runtime.roomLog = roomLog
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
                // The batcher's shared still is redundant now: every trigger
                // captures its own fresh still in sendImmediate and the early
                // row keeps the first one. Skipping it halves camera load.
                captureSnapshot = { null },
                captureVideo = { triggerAt -> runtime.captureVideo(triggerAt) },
                onTriggerExtended = { VideoClipRecorder.extendExport() },
                onBatchClose = { VideoClipRecorder.endExport() },
                maxBatchDuration = MAX_BATCH_DURATION,
                onVideoReady = { batchTime, videoName ->
                    runtime.handleVideoReady(batchTime, videoName)
                },
                // Non-suspending fan-out: the suspend work (still + network)
                // runs in sendImmediate on the runtime scope.
                onImmediateTrigger = { openedAt, trigger ->
                    runtime.runtimeScope.launch { runtime.sendImmediate(openedAt, trigger) }
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
        VideoLinkRegistry.pruneOlderThan(Instant.now().minusSeconds(60))
        // Heal recent orphaned video links (stop-race file-DB desync like
        // 22-19-15-216.mp4 existing but event 142 video_name=null) by
        // checking MediaStore/files for the expected name derived from the
        // event timestamp. Best-effort, runs off the collector scope.
        runtimeScope.launch {
            runCatching {
                val dao = AppDatabase.get(context).eventDao()
                val log = RoomEventLog(dao)
                val recent = log.recent(limit = 50)
                for (ev in recent) {
                    if (ev.videoName != null) continue
                    if (java.time.Duration.between(ev.timestamp, Instant.now()).toHours() > 24) continue
                    val candidate = VideoClipRecorder.videoFileName(ev.timestamp.toEpochMilli(), ev.cameraName)
                    if (VideoClipRecorder.exists(candidate)) {
                        log.updateVideoName(ev.id, candidate)
                        android.util.Log.i(TAG, "healed orphan video link eventId=${ev.id} video=$candidate")
                    }
                }
            }
        }
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
                // Channels were already sent per trigger in sendImmediate;
                // the batch close only finalizes the early row (merged view +
                // preview targets) and queues the clip backup.
                runCatching {
                    android.util.Log.d(TAG, "batch emitted triggers=${it.triggers.size}")
                    finalizeWave(it)
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
        // Slow-device nudge: sample the first gated passes; one slow pass
        // proves the preview will stutter during motion. take() ends the
        // collection after the sample window either way.
        slowCheckJob = runtimeScope.launch {
            val samples = mutableListOf<Long>()
            pipeline.lastGatedMs.filterNotNull().take(SLOW_GATED_SAMPLES).collect {
                samples.add(it)
            }
            if (samples.any { it >= SLOW_GATED_PASS_MS }) {
                _slowDevice.value = true
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
        // Give in-flight video-exports time to link their DB rows before
        // tearing down; TriggerBatcher now launches onVideoReady in a
        // global scope so the link survives runtimeScope cancellation, but
        // awaiting briefly here lets the caller see the linked row without
        // polling.
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val deadline = System.currentTimeMillis() + 15_000L
                while (System.currentTimeMillis() < deadline && VideoLinkRegistry.size() > 0) {
                    kotlinx.coroutines.delay(200)
                }
            }
        }
        runCatching { batchJob?.cancel() }
        batchJob = null
        runCatching { healthJob?.cancel() }
        healthJob = null
        runCatching { slowCheckJob?.cancel() }
        slowCheckJob = null
        _slowDevice.value = false
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
            finalizeWave(batch)
        }.onFailure { t ->
            android.util.Log.w(TAG, "stop batch handling failed", t)
        }
    }

    /**
     * Immediate per-trigger channel send (fast path). Captures a fresh still,
     * delivers to the trigger's routed channels, then records the wave's
     * early row on the first trigger or merges into it afterwards. Late
     * arrivals (after [finalizeWave] marked the wave done) still send their
     * channels and flip statuses, but skip the merged rewrite.
     */
    private suspend fun sendImmediate(batchOpenedAt: Instant, trigger: TriggerEvent) {
        if (stopped) return
        // Frequency gate first: per-wave/per-channel, no snapshot or network
        // unless at least one routed channel is due. A new wave (no state
        // yet) is always due everywhere.
        val nowMs = System.currentTimeMillis()
        val routed = eventPipeline.targetsFor(listOf(trigger))
        val dueIds: Set<String> = waveMutex.withLock {
            val wave = waves[batchOpenedAt]
            routed.filter { it.isDue(wave?.lastSentMs?.get(it.id), nowMs) }
                .map { it.id }.toSet()
        }
        if (dueIds.isEmpty()) {
            // Suppressed by every channel's frequency setting: still merge
            // the trigger into the early row (triggers/score/detail/type),
            // keeping the last send statuses untouched.
            mergeSuppressed(batchOpenedAt, trigger)
            return
        }
        // Fresh still per trigger; waits for the camera (15s timeout inside).
        val snapshot = runCatching { captureSnapshot() }.getOrNull()
        if (stopped) return
        val result = try {
            eventPipeline.sendSingle(trigger, snapshot, onlyChannelIds = dueIds)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "immediate send failed type=${trigger.triggerType}", e)
            return
        }
        // Raw FAILED becomes QUEUED once the outbox row exists (same flip as
        // EventPipeline.handleBatch, but deferred until the early row exists).
        val statuses = LinkedHashMap<String, String>(result.statuses)
        for (target in result.failedTargets) statuses[target.id] = EventPipeline.STATUS_QUEUED

        // Log every channel delivery to the alert log (except the log channel itself, which already logs).
        for ((channelId, status) in statuses) {
            val cfg = settings.channelConfigs.firstOrNull { it.id == channelId }
            if (cfg != null && cfg.type == "log") continue
            AlertLog.add(
                AlertLogEntry(
                    timestamp = trigger.timestamp,
                    channelId = channelId,
                    triggerType = result.type,
                    text = result.text,
                    status = status,
                ),
            )
        }

        var waveEventId = -1L
        var outboxTargets = result.failedTargets
        var backupSnapshot: Snapshot? = null
        waveMutex.withLock {
            var wave = waves[batchOpenedAt]
            if (wave == null) {
                val eventId = try {
                    roomLog.record(
                        RecordedEvent(
                            timestamp = batchOpenedAt,
                            cameraName = settings.cameraName,
                            triggerType = result.type,
                            triggerTypes = emptyList(),
                            score = trigger.score,
                            snapshotName = snapshot?.name,
                            videoName = null,
                            channelStatuses = statuses,
                            detail = trigger.detail,
                        ),
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "immediate record failed", e)
                    return@withLock
                }
                wave = WaveState(
                    triggers = mutableListOf(trigger),
                    eventId = eventId,
                    snapshotName = snapshot?.name,
                    statuses = LinkedHashMap(statuses),
                    lastSentMs = dueIds.associateWith { nowMs }.toMutableMap(),
                )
                waves[batchOpenedAt] = wave
                waveEventId = eventId
                if (snapshot != null) backupSnapshot = snapshot
                // Real id from the start: handleVideoReady never sees the old
                // -1L placeholder in the fast path. Preview targets refresh
                // at finalizeWave when the merged trigger set is known.
                VideoLinkRegistry.put(
                    batchOpenedAt,
                    VideoLinkRegistry.PendingVideoInfo(
                        eventId = eventId,
                        text = result.text,
                        triggerType = result.type,
                        previewTargets = emptyList(),
                    ),
                )
                android.util.Log.d(TAG, "immediate first type=${trigger.triggerType} eventId=$eventId")
            } else if (!wave.finalized) {
                wave.triggers.add(trigger)
                for ((k, v) in statuses) wave.statuses[k] = v
                for (id in dueIds) wave.lastSentMs[id] = nowMs
                val types = wave.triggers.map { it.triggerType }.distinct()
                val mergedType = if (types.size == 1) types.first() else TriggerType.merged
                val mergedDetail = wave.triggers.firstOrNull { !it.detail.isNullOrBlank() }?.detail
                val mergedScore = wave.triggers.maxOfOrNull { it.score } ?: trigger.score
                try {
                    roomLog.updateMerged(
                        eventId = wave.eventId,
                        triggerType = mergedType,
                        triggerTypes = if (types.size == 1) emptyList() else types,
                        score = mergedScore,
                        detail = mergedDetail,
                        channelStatuses = LinkedHashMap(wave.statuses),
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "immediate merge failed eventId=${wave.eventId}", e)
                }
                waveEventId = wave.eventId
                if (snapshot != null) backupSnapshot = snapshot
                android.util.Log.d(TAG, "immediate merge type=${trigger.triggerType} eventId=${wave.eventId} triggers=${wave.triggers.size}")
            } else {
                // Wave already finalized by the batch close: channels for this
                // trigger were still sent above; just flip its failures onto
                // the finished row so the outbox retry stays visible.
                waveEventId = wave.eventId
                for (id in dueIds) wave.lastSentMs[id] = nowMs
                try {
                    for ((k, v) in statuses) {
                        if (v == EventPipeline.STATUS_QUEUED || v == EventPipeline.STATUS_FAILED) {
                            roomLog.flipChannelStatus(wave.eventId, k, v)
                        } else if (wave.statuses[k] != EventPipeline.STATUS_DELIVERED) {
                            roomLog.flipChannelStatus(wave.eventId, k, v)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "immediate late flip failed eventId=${wave.eventId}", e)
                }
                if (snapshot != null) backupSnapshot = snapshot
            }
        }
        if (waveEventId == -1L) return
        // Outbox + cloud backup outside the wave lock.
        if (outboxTargets.isNotEmpty()) {
            runCatching {
                val store = OutboxStore.from(AppDatabase.get(context))
                val now = Instant.now().toEpochMilli()
                for (target in outboxTargets) {
                    store.enqueue(
                        OutboxEntity(
                            createdAt = now,
                            kind = OutboxKind.NOTIFY,
                            channelId = target.id,
                            eventId = waveEventId,
                            triggerType = result.type,
                            eventTime = trigger.timestamp.toEpochMilli(),
                            text = result.text,
                            snapshotName = snapshot?.name,
                        ),
                    )
                }
            }.onFailure { e ->
                android.util.Log.w(TAG, "immediate outbox enqueue failed eventId=$waveEventId", e)
            }
        }
        backupSnapshot?.let { snap ->
            runCatching { queueSnapshotBackup(snap) }.onFailure { e ->
                android.util.Log.w(TAG, "immediate snapshot backup failed snap=${snap.name}", e)
            }
        }
    }

    /**
     * Merges a frequency-suppressed trigger into its wave's early row without
     * touching channels, snapshots, or statuses — only the merged trigger
     * view (types/score/detail) advances. No-op when the wave has no row yet
     * (suppression with no prior send means nothing was ever routed).
     */
    private suspend fun mergeSuppressed(batchOpenedAt: Instant, trigger: TriggerEvent) {
        waveMutex.withLock {
            val wave = waves[batchOpenedAt] ?: return@withLock
            if (wave.finalized) {
                wave.triggers.add(trigger)
                return@withLock
            }
            wave.triggers.add(trigger)
            val types = wave.triggers.map { it.triggerType }.distinct()
            try {
                roomLog.updateMerged(
                    eventId = wave.eventId,
                    triggerType = if (types.size == 1) types.first() else TriggerType.merged,
                    triggerTypes = if (types.size == 1) emptyList() else types,
                    score = wave.triggers.maxOfOrNull { it.score } ?: trigger.score,
                    detail = wave.triggers.firstOrNull { !it.detail.isNullOrBlank() }?.detail,
                    channelStatuses = LinkedHashMap(wave.statuses),
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "suppressed merge failed eventId=${wave.eventId}", e)
            }
        }
    }

    /**
     * Batch-close finalizer (DB-merge path). Channels are NOT resent here —
     * [sendImmediate] already delivered every trigger. Rewrites the early row
     * with the authoritative merged view, refreshes the video-link entry with
     * the final text/type/preview targets, and queues the clip backup.
     * Falls back to the legacy [EventPipeline.handleBatch] only when the fast
     * path never created a row (e.g. every immediate send threw before record).
     */
    private suspend fun finalizeWave(batch: TriggerBatch) {
        val previewTargets = eventPipeline.targetsFor(batch.triggers)
            .filter { t -> t.pushVideoPreview && t.supportsVideoPreview() }
        val finalText = eventPipeline.buildAlertText(batch)
        val finalType = eventPipeline.alertType(batch)
        var knownEventId: Long? = null
        waveMutex.withLock {
            val wave = waves[batch.timestamp]
            if (wave != null) {
                wave.finalized = true
                // Authoritative trigger set: a late trigger's fast send may
                // still be in flight (still + network), so adopt any triggers
                // it hasn't merged yet without dropping its statuses.
                val known = wave.triggers.map { it.timestamp to it.triggerType }.toSet()
                for (t in batch.triggers) {
                    if (!known.contains(t.timestamp to t.triggerType)) wave.triggers.add(t)
                }
                val types = wave.triggers.map { it.triggerType }.distinct()
                val mergedType = if (types.size == 1) types.first() else TriggerType.merged
                val mergedDetail = wave.triggers.firstOrNull { !it.detail.isNullOrBlank() }?.detail
                val mergedScore = wave.triggers.maxOfOrNull { it.score } ?: 0.0
                try {
                    roomLog.updateMerged(
                        eventId = wave.eventId,
                        triggerType = mergedType,
                        triggerTypes = if (types.size == 1) emptyList() else types,
                        score = mergedScore,
                        detail = mergedDetail,
                        channelStatuses = LinkedHashMap(wave.statuses),
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "finalize merge failed eventId=${wave.eventId}", e)
                }
                knownEventId = wave.eventId
                VideoLinkRegistry.put(
                    batch.timestamp,
                    VideoLinkRegistry.PendingVideoInfo(
                        eventId = wave.eventId,
                        text = finalText,
                        triggerType = finalType,
                        previewTargets = previewTargets,
                    ),
                )
                android.util.Log.d(TAG, "wave finalized eventId=${wave.eventId} triggers=${wave.triggers.size} video=${batch.videoName}")
            }
        }
        if (knownEventId != null) {
            // Clip backup only: per-trigger snapshots were already queued in
            // sendImmediate; the batcher's own still is disabled (null).
            if (batch.videoName != null) {
                runCatching {
                    val cb = settings.cloudBackup
                    if (cb.enabled && cb.backupClips) {
                        OutboxStore.from(AppDatabase.get(context)).enqueue(
                            OutboxEntity(
                                createdAt = Instant.now().toEpochMilli(),
                                kind = OutboxKind.BACKUP,
                                mediaPath = "$CLIP_MEDIA_PREFIX${batch.videoName}",
                                remotePath = RemoteKeys.forMedia(settings.cameraName, batch.videoName, Instant.now().toEpochMilli()),
                            ),
                        )
                    }
                }.onFailure { e ->
                    android.util.Log.w(TAG, "finalize clip backup failed video=${batch.videoName}", e)
                }
            }
            return
        }
        // Fallback: no early row (fast path never recorded). Legacy behavior:
        // send channels + record + link, so the wave is never lost.
        VideoLinkRegistry.put(
            batch.timestamp,
            VideoLinkRegistry.PendingVideoInfo(
                eventId = -1L,
                text = finalText,
                triggerType = finalType,
                previewTargets = previewTargets,
            ),
        )
        runCatching {
            val eventId = eventPipeline.handleBatch(batch)
            VideoLinkRegistry.put(
                batch.timestamp,
                VideoLinkRegistry.PendingVideoInfo(
                    eventId = eventId,
                    text = finalText,
                    triggerType = finalType,
                    previewTargets = previewTargets,
                ),
            )
            android.util.Log.d(TAG, "event recorded (fallback) id=$eventId type=${batch.triggers.firstOrNull()?.triggerType} video=${batch.videoName}")
            queueCloudBackups(batch)
        }.onFailure { t ->
            android.util.Log.w(TAG, "batch handling failed", t)
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

    /** Per-trigger snapshot backup for the fast path (clip backup stays late). */
    private suspend fun queueSnapshotBackup(snap: Snapshot) {
        val cb = settings.cloudBackup
        if (!cb.enabled || !cb.backupSnapshots) return
        val now = Instant.now().toEpochMilli()
        OutboxStore.from(AppDatabase.get(context)).enqueue(
            OutboxEntity(
                createdAt = now,
                kind = OutboxKind.BACKUP,
                mediaPath = snap.name,
                remotePath = RemoteKeys.forMedia(settings.cameraName, snap.name, now),
            ),
        )
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
        val info = VideoLinkRegistry.remove(batchTime)
        if (info == null) {
            android.util.Log.w(TAG, "video ready but no eventId for batchTime=$batchTime video=$videoName")
            VideoLinkRegistry.pruneOlderThan(Instant.now().minusSeconds(60))
            return
        }
        if (info.eventId == -1L) {
            android.util.Log.w(TAG, "video ready orphan placeholder for batchTime=$batchTime video=$videoName (handleBatch never succeeded)")
            return
        }
        if (videoName == null) {
            android.util.Log.i(TAG, "video ready null for eventId=${info.eventId} batchTime=$batchTime (no clip)")
            return
        }
        try {
            val log = RoomEventLog(AppDatabase.get(context).eventDao())
            log.updateVideoName(info.eventId, videoName)
            android.util.Log.i(TAG, "video linked eventId=${info.eventId} batchTime=$batchTime video=$videoName")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "video link failed eventId=${info.eventId} video=$videoName", e)
            return
        }
        if (info.previewTargets.isNotEmpty()) {
            pushVideoPreviews(batchTime, info, videoName)
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

    /**
     * Video preview (see docs/plans/2026-09-14-video-preview-gif.md): once
     * the clip muxes, generate a short low-frame-rate GIF from it (only when a
     * routed, capable channel asked for one), persist it beside the snapshot,
     * and push it per channel. An exhausted/delivery failure enqueues an outbox
     * NOTIFY row carrying the preview so connectivity returns re-send it.
     */
    private suspend fun pushVideoPreviews(
        batchTime: Instant,
        info: VideoLinkRegistry.PendingVideoInfo,
        videoName: String,
    ) {
        try {
            val snapshots = FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
            val preview = Mp4PreviewGenerator(context).generate(
                clipName = videoName,
                fps = settings.previewFps,
                maxWidthPx = settings.previewMaxWidthPx,
            ) ?: run {
                android.util.Log.w(TAG, "video preview generation failed video=$videoName")
                return
            }
            val isSheet = settings.previewMode == io.securitycam.level2.core.PreviewMode.SHEET
            val mainPreview = if (isSheet) preview.sheet else preview.video
            if (mainPreview == null) {
                android.util.Log.w(TAG, "video preview missing sheet/video for mode=${settings.previewMode}")
                return
            }
            android.util.Log.i(
                TAG, "video preview generated eventId=${info.eventId} preview=${mainPreview.name} " +
                    "bytes=${mainPreview.bytes.size} mode=${settings.previewMode} targets=${info.previewTargets.size}",
            )
            try {
                snapshots.save(mainPreview)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "video preview save failed preview=${mainPreview.name}", e)
                return
            }
            try {
                snapshots.save(preview.still)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "video preview still save failed still=${preview.still.name}", e)
            }
            preview.sheet?.let {
                if (it !== mainPreview) {
                    try { snapshots.save(it) } catch (e: Exception) {
                        android.util.Log.w(TAG, "video preview sheet save failed sheet=${it.name}", e)
                    }
                }
            }
            val message = AlertMessage(
                timestamp = batchTime,
                triggerType = info.triggerType,
                text = info.text,
                snapshot = preview.still,
                videoPreview = mainPreview,
            )
            val store = OutboxStore.from(AppDatabase.get(context))
            android.util.Log.w(TAG, "pushVideoPreviews: previewTargets size=${info.previewTargets.size}, targets=${info.previewTargets.map { "${it.id}(${it.type},push=${it.pushVideoPreview},supports=${it.supportsVideoPreview()})" }.joinToString(", ")}")
            for (target in info.previewTargets) {
                android.util.Log.w(TAG, "video preview sending to channel id=${target.id} type=${target.type} pushVideoPreview=${target.pushVideoPreview} supportsVideoPreview=${target.supportsVideoPreview()}")
                val factory = ChannelRegistry.factories[target.type] ?: continue
                val delivered = try {
                    factory(target).send(message)
                    true
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "video preview send failed eventId=${info.eventId} channel=${target.id}", e)
                    false
                }
                if (!delivered) {
                    try {
                        store.enqueue(
                            OutboxEntity(
                                createdAt = Instant.now().toEpochMilli(),
                                kind = OutboxKind.NOTIFY,
                                channelId = target.id,
                                eventId = info.eventId,
                                triggerType = info.triggerType,
                                eventTime = batchTime.toEpochMilli(),
                                text = info.text,
                                previewGifName = mainPreview.name,
                            ),
                        )
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "video preview outbox enqueue failed eventId=${info.eventId}", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "video preview push aborted eventId=${info.eventId}", e)
        }
    }
}