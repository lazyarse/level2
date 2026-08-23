package io.securitycam.level1.monitor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.camera_service.CameraEvents
import io.securitycam.level1.camera_service.MonitoringService
import io.securitycam.level1.camera_service.MonitoringServiceController
import io.securitycam.level1.camera_service.VideoClipRecorder
import io.securitycam.level1.camera_service.availableCameras
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.SchedulePolicy
import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.EncryptedSecretStore
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RoomEventLog
import io.securitycam.level1.storage.SettingsStore
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Monitor state machine. Ports the Dart `MonitorController` lifecycle for the
 * native app: permission gate, FGS start/stop, preview-status surface and error
 * surfacing. Settings and the full detection pipeline wire in (Phase 4); for
 * now the runtime is the camera service with placeholder defaults.
 *
 * The side effects are injectable so state transitions are unit-testable
 * without an emulator (Robolectric).
 */
class MonitorViewModel(
    application: Application,
    private val permissionsGranted: () -> Boolean = {
        hasCorePermissions(application)
    },
    private val startMonitoring: (cameraId: String) -> Unit = { cameraId ->
        MonitoringService.start(
            application,
            cameraId = cameraId,
            cameraName = "Hallway",
            preRollSeconds = 5,
            postRollSeconds = 5,
            recordVideo = true,
            videoQuality = "lowest",
        )
    },
    private val stopMonitoring: () -> Unit = {
        MonitoringServiceController.stop()
    },
    private val settingsLoader: suspend () -> AppSettings = {
        settingsStoreFor(application).load()
    },
    private val settingsSaver: suspend (AppSettings) -> Unit = { settings ->
        settingsStoreFor(application).save(settings)
    },
    private val scheduleCheckInterval: Duration? = Duration.ofMinutes(1),
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    /**
     * Whether a detection-runtime initialization failure flips the session to
     * [MonitorState.Error] instead of only logging. Robolectric JVM tests cannot
     * initialize the native detectors, so they opt out.
     */
    private val surfaceRuntimeStartFailures: Boolean = true,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MonitorState.Idle)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _scheduleNote = MutableStateFlow<String?>(null)
    val scheduleNote: StateFlow<String?> = _scheduleNote.asStateFlow()

    private val _schedulePaused = MutableStateFlow(false)
    val schedulePaused: StateFlow<Boolean> = _schedulePaused.asStateFlow()

    private val _previewActive = MutableStateFlow(false)
    val previewActive: StateFlow<Boolean> = _previewActive.asStateFlow()

    private val _cameraName = MutableStateFlow("Hallway")
    val cameraName: StateFlow<String> = _cameraName.asStateFlow()

    private val _cameraId = MutableStateFlow("0")
    val cameraId: StateFlow<String> = _cameraId.asStateFlow()

    private val _detectionRegions = MutableStateFlow<List<DetectionRegion>>(emptyList())
    val detectionRegions: StateFlow<List<DetectionRegion>> = _detectionRegions.asStateFlow()
    private val _exclusionRegions = MutableStateFlow<List<DetectionRegion>>(emptyList())
    val exclusionRegions: StateFlow<List<DetectionRegion>> = _exclusionRegions.asStateFlow()

    private val _healthStalled = MutableStateFlow(false)
    val healthStalled: StateFlow<Boolean> = _healthStalled.asStateFlow()

    private val _activeTriggers = MutableStateFlow<Set<String>>(emptySet())
    val activeTriggers: StateFlow<Set<String>> = _activeTriggers.asStateFlow()

    private var runtime: MonitoringRuntime? = null
    private var healthJob: Job? = null
    private var triggerCollectorJob: Job? = null

    /**
     * Pulses status-bar icons on every trigger EVENT (edge-based). The
     * runtime's accumulating StateFlow<Set> conflates identical consecutive
     * sets, which swallowed repeat triggers after the icon timeout.
     */
    private val iconPulser = TriggerIconPulser(
        viewModelScope,
        TRIGGER_ICON_DURATION_MS,
    ) { types -> _activeTriggers.value = types }

    // Invalidates in-flight start coroutines when stop() wins the race.
    private var startGeneration = 0

    private var scheduleJob: Job? = null
    private var scheduleSettings: AppSettings? = null

    private val previewStatusListener: (Boolean) -> Unit = { active ->
        _previewActive.value = active
    }

    init {
        CameraEvents.addPreviewStatusListener(previewStatusListener)
        // Schedule enforcement tick (design: auto-stop on entering an exclusion,
        // auto-resume on leaving if monitoring was running before).
        scheduleCheckInterval?.let { interval ->
            scheduleJob = viewModelScope.launch {
                while (isActive) {
                    delay(interval.toMillis())
                    runCatching { checkScheduleNow() }
                }
            }
        }
    }

    /** Permissions surfaced to the UI for `RequestMultiplePermissions`. */
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(getApplication(), it) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** CAMERA + RECORD_AUDIO gate monitoring; POST_NOTIFICATIONS is non-fatal. */
    fun hasCorePermissions(): Boolean = hasCorePermissions(getApplication())

    companion object {
        private const val TAG = "MonitorViewModel"
        private const val TRIGGER_ICON_DURATION_MS = 4000L
        private const val RUNTIME_STOP_TIMEOUT_MS = 3_000L

        // Memoized per-process: SettingsStore wraps an AndroidKeyStore-backed
        // secret store whose construction is expensive enough that rebuilding
        // it on every load/save call (including each schedule tick) is wasteful.
        private val settingsStores =
            java.util.concurrent.ConcurrentHashMap<Application, SettingsStore>()

        private fun settingsStoreFor(app: Application): SettingsStore =
            settingsStores.getOrPut(app) { SettingsStore(app, EncryptedSecretStore(app)) }

        /** Explicit factory: the default owner factory can't build an AndroidViewModel. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MonitorViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        ?: error("Application missing from initializer"),
                )
            }
        }

        fun hasCorePermissions(context: Application): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** Called when the permission prompt returns with core grants missing. */
    fun onPermissionsDenied() {
        _state.value = MonitorState.Error
        _error.value =
            "Camera and microphone permissions are required to monitor — " +
                "grant them in system Settings and try again."
    }

    fun start() {
        if (_state.value == MonitorState.Monitoring) return
        if (!permissionsGranted()) {
            onPermissionsDenied()
            return
        }
        // If previewing, stop preview first so the controller is not active.
        if (_state.value == MonitorState.Previewing) {
            MonitoringServiceController.stopPreviewOnly()
        }
        // Manual start is blocked while a schedule exclusion is active (cached
        // settings only — before the first load we cannot know yet).
        scheduleSettings?.let { cached ->
            if (SchedulePolicy.isExcluded(cached.scheduleExclusions, nowProvider())) {
                _scheduleNote.value =
                    "Monitoring is paused during a scheduled exclusion"
                return
            }
        }
        _schedulePaused.value = false
        _scheduleNote.value = null
        _state.value = MonitorState.Starting
        _error.value = null
        // Use cached cameraId (or default "0") for synchronous service start;
        // full settings are loaded in the coroutine for runtime creation.
        startMonitoring(_cameraId.value)
        _state.value = MonitorState.Monitoring
        val gen = ++startGeneration
        // Build the detection→event runtime off the main thread; the service
        // (camera + mic) is already streaming by the time it subscribes.
        viewModelScope.launch {
            // Settings load failures are always real bugs — surface them.
            val settings: AppSettings
            try {
                settings = settingsLoader()
                scheduleSettings = settings
                // A stop (manual or scheduled) may have won the race while we
                // were suspended; abandon this start instead of leaking a live
                // pipeline into an Idle session.
                if (gen != startGeneration || _state.value != MonitorState.Monitoring) {
                    return@launch
                }
                _cameraName.value = settings.cameraName
                _cameraId.value = settings.cameraId
                _detectionRegions.value = settings.detectionRegions
                _exclusionRegions.value = settings.exclusionRegions
            } catch (t: Throwable) {
                Log.w(TAG, "settings load failed", t)
                failStart(gen, t)
                return@launch
            }
            try {
                MonitoringRuntime.create(getApplication(), settings, viewModelScope).let { created ->
                    if (gen != startGeneration || _state.value != MonitorState.Monitoring) {
                        created.stop()
                        return@let
                    }
                    runtime = created
                    healthJob = viewModelScope.launch {
                        created.healthStalled.collect { stalled -> _healthStalled.value = stalled }
                    }
                    triggerCollectorJob = viewModelScope.launch {
                        created.triggerEvents.collect { event ->
                            iconPulser.onEvent(event.triggerType)
                        }
                    }
                    created.begin()
                }
                if (gen == startGeneration && _state.value == MonitorState.Monitoring) {
                    purgeOldEvents(settings)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "runtime start failed", t)
                if (surfaceRuntimeStartFailures) failStart(gen, t)
            }
        }
    }

    private fun failStart(gen: Int, t: Throwable) {
        if (gen != startGeneration || _state.value != MonitorState.Monitoring) return
        _state.value = MonitorState.Error
        _error.value = "Monitoring failed to start: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun cancelTriggerJobs() {
        triggerCollectorJob?.cancel()
        triggerCollectorJob = null
        iconPulser.reset()
    }

    /** Shared post-stop cleanup: cancel collectors/timers and dispose the runtime. */
    private fun teardownMonitoring() {
        _healthStalled.value = false
        _activeTriggers.value = emptySet()
        healthJob?.cancel()
        healthJob = null
        cancelTriggerJobs()
        val current = runtime
        runtime = null
        viewModelScope.launch { current?.stop() }
    }

    fun stop() {
        // A manual stop always cancels any pending auto-resume.
        _schedulePaused.value = false
        if (_state.value == MonitorState.Idle) return
        startGeneration++
        stopMonitoring()
        _state.value = MonitorState.Idle
        teardownMonitoring()
    }

    fun startPreview() {
        if (_state.value == MonitorState.Previewing || _state.value == MonitorState.Monitoring) return
        if (!permissionsGranted()) {
            onPermissionsDenied()
            return
        }
        _error.value = null
        _state.value = MonitorState.Previewing
        MonitoringService.startPreview(getApplication(), _cameraId.value)
    }

    fun stopPreview() {
        if (_state.value != MonitorState.Previewing) return
        MonitoringServiceController.stopPreviewOnly()
        _state.value = MonitorState.Idle
    }

    /** Cycle to the next available camera. Stops monitoring, updates the setting, and restarts. */
    fun cycleCamera() {
        viewModelScope.launch {
            val settings = settingsLoader()
            val cameras = availableCameras(getApplication())
            if (cameras.size <= 1) return@launch
            val currentIndex = cameras.indexOfFirst { it.id == settings.cameraId }
            val nextIndex = (currentIndex + 1) % cameras.size
            val nextId = cameras[nextIndex].id
            val updated = settings.copy(cameraId = nextId)
            settingsSaver(updated)
            scheduleSettings = updated
            // Restart below must bind the NEW camera: start()/startPreview()
            // read the cached id synchronously.
            _cameraId.value = nextId
            Toast.makeText(
                getApplication(),
                cameras[nextIndex].label,
                Toast.LENGTH_SHORT,
            ).show()
            // Restart monitoring/preview with the new camera if currently active
            val currentState = _state.value
            if (currentState == MonitorState.Monitoring) {
                stop()
                start()
            } else if (currentState == MonitorState.Previewing) {
                stopPreview()
                startPreview()
            }
        }
    }

    /**
     * Schedule enforcement, run by the periodic tick and directly from tests:
     * auto-stop when monitoring enters an exclusion window, auto-resume when
     * it leaves (only if the schedule stopped it).
     */
    suspend fun checkScheduleNow() {
        val settings = settingsLoader()
        scheduleSettings = settings
        val excluded = SchedulePolicy.isExcluded(settings.scheduleExclusions, nowProvider())
        when {
            _state.value == MonitorState.Monitoring && excluded -> {
                _schedulePaused.value = true
                _scheduleNote.value = "Monitoring paused — scheduled exclusion"
                startGeneration++
                stopMonitoring()
                _state.value = MonitorState.Idle
                teardownMonitoring()
            }

            _state.value == MonitorState.Idle &&
                _schedulePaused.value &&
                !excluded -> {
                _schedulePaused.value = false
                start()
            }
        }
    }

    /**
     * Retention purge: deletes event rows older than [AppSettings.retentionDays]
     * along with their snapshots and clips (port of the Dart purge logic).
     */
    private suspend fun purgeOldEvents(settings: AppSettings) {
        val context = getApplication<Application>()
        val cutoff = Instant.now().minus(Duration.ofDays(settings.retentionDays.toLong()))
        val deleted = RoomEventLog(AppDatabase.get(context).eventDao()).deleteEvents(cutoff)
        val snapshots = FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
        for (name in deleted.snapshotNames) {
            runCatching { snapshots.delete(name) }
        }
        for (name in deleted.videoNames) {
            runCatching { VideoClipRecorder.delete(name) }
        }
    }

    override fun onCleared() {
        CameraEvents.removePreviewStatusListener(previewStatusListener)
        scheduleJob?.cancel()
        scheduleJob = null
        startGeneration++
        cancelTriggerJobs()
        val current = runtime
        runtime = null
        if (current != null) {
            // viewModelScope is already cancelled here and the runtime teardown
            // joins work dispatched on the main looper — blocking on the main
            // thread would deadlock/ANR. Tear down off-main with a bounded wait;
            // the FGS stop below stays synchronous so the service always dies.
            Thread {
                try {
                    runBlocking {
                        withTimeoutOrNull(RUNTIME_STOP_TIMEOUT_MS) { current.stop() }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "runtime teardown on clear failed", t)
                }
            }.apply { isDaemon = true }.start()
        }
        stopMonitoring()
        super.onCleared()
    }
}