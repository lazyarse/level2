package io.securitycam.level1.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.camera_service.MonitoringService
import io.securitycam.level1.camera_service.MonitoringServiceController
import io.securitycam.level1.camera_service.VideoClipRecorder
import io.securitycam.level1.channels.ChannelRegistry
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.detection.face.FaceEmbeddingEngine
import io.securitycam.level1.detection.face.MediaPipeFaceEngine
import io.securitycam.level1.event.ChannelFactory
import io.securitycam.level1.identity.FaceEnrollmentCoordinator
import io.securitycam.level1.identity.KnownFaceStore
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.EncryptedSecretStore
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RoomEventLog
import io.securitycam.level1.storage.SettingsStore
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Draft-commit settings state (port of the Flutter `SettingsScreen._draft` +
 * `MonitorController.updateSettings` pattern). Loads once, mutates a draft,
 * and persists on save. Event clearing mirrors the Dart controller's
 * `_deleteOlderThan`: rows first, then snapshot files and gallery clips.
 */
class SettingsViewModel(
    private val application: Application? = null,
    private val settingsLoader: suspend () -> AppSettings,
    private val settingsSaver: suspend (AppSettings) -> Unit,
    private val eventsClearer: suspend (Duration?) -> Unit,
    private val channelFactories: Map<String, ChannelFactory> = ChannelRegistry.factories,
    private val enrollmentFactory: () -> FaceEnrollmentCoordinator? = { null },
    /** True when a camera session is already publishing frames to the bus. */
    private val cameraActive: () -> Boolean = {
        MonitoringServiceController.cameraActive()
    },
    private val startCameraSession: (cameraId: String) -> Unit = { cameraId ->
        application?.let { MonitoringService.startPreview(it, cameraId) }
    },
    private val stopCameraSession: () -> Unit = {
        MonitoringServiceController.stopPreviewOnly()
    },
    /** Wait bounds for the temporary session bind; tests shrink these. */
    private val framesWaitTimeoutMs: Long = 5_000L,
    private val framesSettleMs: Long = 500L,
) : ViewModel() {

    /** Null until the stored settings finish loading. */
    private val _draft = MutableStateFlow<AppSettings?>(null)
    val draft: StateFlow<AppSettings?> = _draft.asStateFlow()

    /** One-shot snackbar text; consume with [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Channel id whose "Send test" is in flight (button disabled). */
    private val _sendingTestId = MutableStateFlow<String?>(null)
    val sendingTestId: StateFlow<String?> = _sendingTestId.asStateFlow()

    /** Enrollment progress: the label being enrolled, or null when idle. */
    private val _enrollingLabel = MutableStateFlow<String?>(null)
    val enrollingLabel: StateFlow<String?> = _enrollingLabel.asStateFlow()

    /** In-flight enrollment coroutine, for cancellation from the UI. */
    private var enrollmentJob: kotlinx.coroutines.Job? = null

    /** Factories exposed so the UI can gate the send-test button on validate(). */
    val testFactories: Map<String, ChannelFactory> get() = channelFactories

    init {
        viewModelScope.launch {
            _draft.value = settingsLoader()
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save() {
        val current = _draft.value ?: return
        viewModelScope.launch {
            settingsSaver(current)
            _message.value = "Settings saved"
        }
    }

    fun clearEvents(olderThan: Duration?) {
        viewModelScope.launch {
            eventsClearer(olderThan)
            _message.value = "Events cleared"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Sends a test alert through [config]'s channel (design:
     * `2026-08-19-channel-sendtest-design.md`). Returns "delivered",
     * "invalid: <reason>" (validate short-circuit, no network), or
     * "failed: <error>".
     */
    suspend fun sendTest(config: io.securitycam.level1.core.ChannelConfig): String {
        val factory = channelFactories[config.type]
            ?: return "failed: unknown channel type ${config.type}"
        val channel = factory(config)
        val invalid = channel.validate()
        if (invalid != null) return "invalid: $invalid"
        return try {
            channel.sendTest()
            "delivered"
        } catch (t: Throwable) {
            "failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** UI entry point: runs [sendTest] and surfaces the result as a snackbar. */
    fun sendTestFromUi(config: io.securitycam.level1.core.ChannelConfig) {
        if (_sendingTestId.value != null) return
        _sendingTestId.value = config.id
        viewModelScope.launch {
            _message.value = "Send test: ${sendTest(config)}"
            _sendingTestId.value = null
        }
    }

    /**
     * Enrols [label] using the live camera bus. If no camera session is
     * running (monitoring or preview), starts a temporary preview-only
     * session — its analysis feed supplies frames — and stops it afterwards;
     * a user-started session is left alone.
     */
    fun startEnrollment(label: String) {
        val coordinator = enrollmentFactory()
        if (coordinator == null) {
            _message.value = "Face enrollment unavailable"
            return
        }
        if (_enrollingLabel.value != null) return
        enrollmentJob = viewModelScope.launch {
            _enrollingLabel.value = label
            try {
                val weStartedCamera = !cameraActive()
                try {
                    if (weStartedCamera) {
                        val cameraId = _draft.value?.cameraId
                            ?: settingsLoader().cameraId
                        startCameraSession(cameraId)
                        check(awaitFramesFlowing()) { "Camera did not start" }
                    }
                    val result = coordinator.enroll(label)
                    _message.value = if (result.isSuccess) {
                        "Enrolled ${result.getOrThrow().label}"
                    } else {
                        "Enroll failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    }
                } finally {
                    if (weStartedCamera) stopCameraSession()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _message.value = "Enrollment cancelled"
                throw e
            } catch (e: Exception) {
                _message.value = "Enroll failed: ${e.message ?: "unknown error"}"
            } finally {
                enrollmentJob = null
                _enrollingLabel.value = null
            }
        }
    }

    /** Cancels the in-flight enrollment; session teardown runs via finally. */
    fun cancelEnrollment() {
        enrollmentJob?.cancel()
    }

    /**
     * Waits until the temporary session is bound (frames flowing), with a
     * short settle so the first published frames reach bus listeners. False on
     * timeout.
     */
    private suspend fun awaitFramesFlowing(): Boolean =
        try {
            withTimeout(framesWaitTimeoutMs) {
                while (!cameraActive()) delay(100)
            }
            delay(framesSettleMs)
            true
        } catch (_: TimeoutCancellationException) {
            false
        }

    /** Remove a face from the enrolled list and delete its centroid file. */
    fun deleteFace(face: KnownFace, store: KnownFaceStore) {
        viewModelScope.launch {
            val current = _draft.value ?: return@launch
            store.delete(face.id)
            _draft.value = current.copy(
                knownFaces = current.knownFaces.filterNot { it.id == face.id },
            )
            _message.value = "Removed ${face.label}"
        }
    }

    companion object {
        /**
         * Default event purge used by the Settings screen buttons (and the same
         * shape as MonitorViewModel's retention sweep).
         */
        fun defaultEventsClearer(application: Application): suspend (Duration?) -> Unit =
            { olderThan ->
                val context = application.applicationContext
                val cutoff = olderThan?.let { Instant.now().minus(it) }
                val deleted = RoomEventLog(AppDatabase.get(context).eventDao()).deleteEvents(cutoff)
                val snapshots = FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
                for (name in deleted.snapshotNames) {
                    runCatching { snapshots.delete(name) }
                }
                for (name in deleted.videoNames) {
                    runCatching { VideoClipRecorder.delete(name) }
                }
            }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as? Application
                    ?: error("Application missing from initializer")
                SettingsViewModel(
                    application = app,
                    settingsLoader = {
                        SettingsStore(app, EncryptedSecretStore(app)).load()
                    },
                    settingsSaver = { settings ->
                        SettingsStore(app, EncryptedSecretStore(app)).save(settings)
                    },
                    eventsClearer = defaultEventsClearer(app),
                    enrollmentFactory = {
                        FaceEnrollmentCoordinator(
                            store = KnownFaceStore(app),
                            embedder = FaceEmbeddingEngine.load(app),
                            faceFinder = FaceEnrollmentCoordinator.busFinder(
                                engineFactory = { MediaPipeFaceEngine(app) },
                            ),
                            settingsLoader = {
                                SettingsStore(app, EncryptedSecretStore(app)).load()
                            },
                            settingsSaver = {
                                SettingsStore(app, EncryptedSecretStore(app)).save(it)
                            },
                        )
                    },
                )
            }
        }
    }
}
