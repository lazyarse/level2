package io.securitycam.level1.monitor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.camera_service.CameraEvents
import io.securitycam.level1.camera_service.MonitoringService
import io.securitycam.level1.camera_service.MonitoringServiceController
import io.securitycam.level1.detection.DetectionRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MonitorState.Idle)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _previewActive = MutableStateFlow(false)
    val previewActive: StateFlow<Boolean> = _previewActive.asStateFlow()

    private val _cameraName = MutableStateFlow("Hallway")
    val cameraName: StateFlow<String> = _cameraName.asStateFlow()

    private val _detectionRegions = MutableStateFlow<List<DetectionRegion>>(emptyList())
    val detectionRegions: StateFlow<List<DetectionRegion>> = _detectionRegions.asStateFlow()

    private val previewStatusListener: (Boolean) -> Unit = { active ->
        _previewActive.value = active
    }

    init {
        CameraEvents.addPreviewStatusListener(previewStatusListener)
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
        _state.value = MonitorState.Starting
        _error.value = null
        startMonitoring()
        _state.value = MonitorState.Monitoring
    }

    fun stop() {
        if (_state.value == MonitorState.Idle) return
        stopMonitoring()
        _state.value = MonitorState.Idle
    }

    override fun onCleared() {
        CameraEvents.removePreviewStatusListener(previewStatusListener)
        stopMonitoring()
        super.onCleared()
    }
}