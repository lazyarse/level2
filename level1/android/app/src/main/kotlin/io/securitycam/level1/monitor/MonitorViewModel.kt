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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private val startMonitoring: () -> Unit = {
        MonitoringService.start(
            application,
            cameraId = "0",
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
        SettingsStore(application, EncryptedSecretStore(application)).load()
    },
    private val scheduleCheckInterval: Duration? = Duration.ofMinutes(1),
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
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

    private val _detectionRegions = MutableStateFlow<List<DetectionRegion>>(emptyList())
    val detectionRegions: StateFlow<List<DetectionRegion>> = _detectionRegions.asStateFlow()

    private val _healthStalled = MutableStateFlow(false)
    val healthStalled: StateFlow<Boolean> = _healthStalled.asStateFlow()

    private var runtime: MonitoringRuntime? = null
    private var healthJob: kotlinx.coroutines.Job? = null
    private var scheduleJob: kotlinx.coroutines.Job? = null
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
        startMonitoring()
        _state.value = MonitorState.Monitoring
        // Build the detection→event runtime off the main thread; the service
        // (camera + mic) is already streaming by the time it subscribes.
        viewModelScope.launch {
            try {
                val settings = settingsLoader()
                scheduleSettings = settings
                _cameraName.value = settings.cameraName
                _detectionRegions.value = settings.detectionRegions
                MonitoringRuntime.create(getApplication(), settings, viewModelScope).let {
                    runtime = it
                    healthJob = viewModelScope.launch {
                        it.healthStalled.collect { stalled -> _healthStalled.value = stalled }
                    }
                    it.begin()
                }
                purgeOldEvents(settings)
            } catch (t: Throwable) {
                Log.w(TAG, "runtime start failed", t)
            }
        }
    }

    fun stop() {
        // A manual stop always cancels any pending auto-resume.
        _schedulePaused.value = false
        if (_state.value == MonitorState.Idle) return
        stopMonitoring()
        _state.value = MonitorState.Idle
        _healthStalled.value = false
        val current = runtime
        runtime = null
        healthJob?.cancel()
        healthJob = null
        viewModelScope.launch { current?.stop() }
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
                stopMonitoring()
                _state.value = MonitorState.Idle
                _healthStalled.value = false
                val current = runtime
                runtime = null
                healthJob?.cancel()
                healthJob = null
                current?.stop()
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
        val current = runtime
        runtime = null
        if (current != null) {
            runBlocking { current.stop() }
        }
        stopMonitoring()
        super.onCleared()
    }
}