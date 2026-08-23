package io.securitycam.level1.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.face.FaceDetection
import io.securitycam.level1.detection.face.FaceEmbeddingEngine
import io.securitycam.level1.detection.face.MediaPipeFaceEngine
import io.securitycam.level1.event.ChannelFactory
import io.securitycam.level1.identity.FaceDirectory
import io.securitycam.level1.identity.FaceEnrollmentCoordinator
import io.securitycam.level1.identity.FaceThumbs
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
    /** Builds a coordinator wired to the VM's capture stash. */
    private val enrollmentFactory:
        (onCapture: (ColorBitmap, FaceDetection) -> Unit) -> FaceEnrollmentCoordinator? =
        { null },
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
    /** In-place front/back rebind for a running preview-only session. */
    private val switchPreviewCamera: (cameraId: String) -> Unit = {
        MonitoringServiceController.switchPreviewCamera(it)
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

    /**
     * Session-only front-camera preference for the enrollment capture screen.
     * Deliberately not persisted: monitoring keeps its configured camera.
     */
    private val _enrollmentFrontCamera = MutableStateFlow(false)
    val enrollmentFrontCamera: StateFlow<Boolean> = _enrollmentFrontCamera.asStateFlow()

    /** True while enrollment started its own temporary camera session. */
    private val _enrollmentSessionLocal = MutableStateFlow(false)
    val enrollmentSessionLocal: StateFlow<Boolean> = _enrollmentSessionLocal.asStateFlow()

    // Camera id currently requested for the enrollment session.
    private var sessionCameraId: String = "0"

    // Frame/box from the most recent capture in the active enrollment; used
    // to persist a thumbnail once the enrollment succeeds.
    private var pendingCapture: Pair<ColorBitmap, FaceDetection>? = null

    /** Person store for centroid/thumbnail files; null without an app. */
    private val faceStore: KnownFaceStore? by lazy {
        application?.let { KnownFaceStore(it) }
    }

    /** Thumbnail file for [faceId], or null when storage is unavailable. */
    fun thumbFile(faceId: String): File? =
        application?.let { KnownFaceStore(it).thumbFileFor(faceId) }

    /** Merged-sample count for [faceId] (photos folded into the centroid). */
    fun sampleCount(faceId: String): Int =
        application?.let { KnownFaceStore(it).sampleCount(faceId) } ?: 0

    /** Factories exposed so the UI can gate the send-test button on validate(). */
    val testFactories: Map<String, ChannelFactory> get() = channelFactories

    init {
        viewModelScope.launch {
            _draft.value = settingsLoader()
            _draft.value?.let { FaceDirectory.setAll(it.knownFaces) }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save() {
        val current = _draft.value ?: return
        viewModelScope.launch {
            settingsSaver(current)
            FaceDirectory.setAll(current.knownFaces)
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
     * Enrols a NEW person [label] using the live camera bus. Duplicate names
     * are rejected up-front (the list row's add-photos action extends an
     * existing person instead).
     */
    fun startEnrollment(label: String) {
        val trimmed = label.trim()
        if (_draft.value?.knownFaces?.any { it.label.equals(trimmed, ignoreCase = true) } == true) {
            _message.value =
                "$trimmed is already enrolled — use the photos icon to add more angles"
            return
        }
        launchEnrollment(trimmed, sample = false) { it.enroll(trimmed) }
    }

    /** Adds another captured angle for an existing person. */
    fun startSampleCapture(face: KnownFace) {
        if (_enrollingLabel.value != null) return
        launchEnrollment(face.label, sample = true) { it.addSample(face.id) }
    }

    private fun launchEnrollment(
        progressLabel: String,
        sample: Boolean,
        block: suspend (FaceEnrollmentCoordinator) -> Result<KnownFace>,
    ) {
        val coordinator = enrollmentFactory { frame, det -> pendingCapture = frame to det }
        if (coordinator == null) {
            _message.value = "Face enrollment unavailable"
            return
        }
        val missing = missingEnrollmentPermissions()
        if (missing.isNotEmpty()) {
            _message.value = "Camera permission is required to enrol a face"
            return
        }
        if (_enrollingLabel.value != null) return
        // Session camera choice resets every capture (session-only).
        _enrollmentFrontCamera.value = false
        sessionCameraId = baseEnrollmentCameraId()
        pendingCapture = null
        enrollmentJob = viewModelScope.launch {
            _enrollingLabel.value = progressLabel
            try {
                val weStartedCamera = !cameraActive()
                _enrollmentSessionLocal.value = weStartedCamera
                try {
                    if (weStartedCamera) {
                        startCameraSession(sessionCameraId)
                        check(awaitFramesFlowing()) { "Camera did not start" }
                        // Heal a flip that landed before the service was up.
                        switchPreviewCamera(sessionCameraId)
                    }
                    val result = block(coordinator)
                    var enrolledFace: KnownFace? = null
                    var enabledSuffix = ""
                    result.getOrNull()?.let { face ->
                        enrolledFace = face
                        persistThumbnail(face.id)
                        syncFaceIntoDraft(face)
                        _draft.value?.let { FaceDirectory.setAll(it.knownFaces) }
                        // First-class feature enablement: recognition is a
                        // no-op until its routing configs exist, so seed them
                        // on enroll and persist immediately (restart needed
                        // for a live session to pick the recognizer up).
                        val latest = _draft.value
                        if (latest != null && !AppSettings.faceRecognitionEnabled(latest)) {
                            update {
                                with(AppSettings) { it.withFaceRecognition(true) }
                            }
                            enabledSuffix =
                                " — face recognition enabled; restart monitoring to apply"
                            _draft.value?.let { d ->
                                viewModelScope.launch {
                                    runCatching { settingsSaver(d) }
                                }
                            }
                        }
                    }
                    _message.value = when {
                        result.isSuccess && sample ->
                            "Added photo for ${enrolledFace?.label}" + enabledSuffix
                        result.isSuccess ->
                            "Enrolled ${enrolledFace?.label}" + enabledSuffix
                        else ->
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
                pendingCapture = null
                _enrollmentSessionLocal.value = false
                _enrollingLabel.value = null
            }
        }
    }

    /** Keeps the settings draft in step so the UI and Save reflect enrollments. */
    private fun syncFaceIntoDraft(face: KnownFace) {
        val current = _draft.value ?: return
        _draft.value = current.copy(
            knownFaces = current.knownFaces.filterNot { it.id == face.id } + face,
        )
    }

    /** Persists the stashed capture as `<id>.jpg`; best-effort, never fatal. */
    private fun persistThumbnail(faceId: String) {
        val app = application ?: return
        val (frame, det) = pendingCapture ?: return
        runCatching {
            FaceThumbs.writeJpg(
                File(app.filesDir, KnownFaceStore.DIR_NAME),
                faceId,
                frame,
                doubleArrayOf(det.x1, det.y1, det.x2, det.y2),
            )
        }.onFailure { android.util.Log.w("FaceEnroll", "thumbnail write failed", it) }
    }

    /** CAMERA is the only permission face enrollment needs (no audio). */
    fun missingEnrollmentPermissions(): List<String> {
        val app = application ?: return emptyList()
        return listOf(Manifest.permission.CAMERA).filter {
            ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** Surfaced when the enrollment permission prompt is denied. */
    fun notifyEnrollmentPermissionDenied() {
        _message.value = "Camera permission is required to enrol a face"
    }

    /** Surfaced when a duplicate name is entered in the Enrol dialog. */
    fun notifyDuplicateName(name: String) {
        _message.value = "$name is already enrolled — use the photos icon to add more angles"
    }

    /**
     * Front/back flip on the capture screen. Session-only: toggles between
     * the front camera and the persisted (non-front) monitoring camera, and
     * rebinds a running local session in place. Ignored while another
     * session (monitoring) owns the camera.
     */
    fun flipEnrollmentCamera() {
        // Only when enrollment owns a local session (never monitoring's).
        if (!_enrollmentSessionLocal.value) return
        sessionCameraId =
            if (isFrontId(sessionCameraId)) backEnrollmentCameraId() else FRONT_CAMERA_ID
        _enrollmentFrontCamera.value = isFrontId(sessionCameraId)
        switchPreviewCamera(sessionCameraId)
    }

    private fun baseEnrollmentCameraId(): String = _draft.value?.cameraId ?: "0"

    private fun backEnrollmentCameraId(): String {
        val base = baseEnrollmentCameraId()
        return if (isFrontId(base)) BACK_CAMERA_ID else base
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

    /** Remove a face: centroid, thumbnail, and draft entry. */
    fun deleteFace(face: KnownFace) {
        viewModelScope.launch {
            val current = _draft.value ?: return@launch
            faceStore?.delete(face.id)
            _draft.value = current.copy(
                knownFaces = current.knownFaces.filterNot { it.id == face.id },
            )
            _draft.value?.let { FaceDirectory.setAll(it.knownFaces) }
            _message.value = "Removed ${face.label}"
        }
    }

    companion object {
        private const val FRONT_CAMERA_ID = "front"
        private const val BACK_CAMERA_ID = "back"

        /** Matches MonitoringServiceController.cameraSelectorFor legacy keys. */
        fun isFrontId(cameraId: String): Boolean = cameraId == "front" || cameraId == "1"

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
                // One interpreter for the app's lifetime: loading per enrollment
                // tap leaked a native TFLite tensor arena per attempt until
                // allocations failed. Kept separate from MonitoringRuntime's
                // instance (interpreters aren't thread-safe across consumers).
                val enrollmentEmbedder = FaceEmbeddingEngine.load(app)
                SettingsViewModel(
                    application = app,
                    settingsLoader = {
                        SettingsStore(app, EncryptedSecretStore(app)).load()
                    },
                    settingsSaver = { settings ->
                        SettingsStore(app, EncryptedSecretStore(app)).save(settings)
                    },
                    eventsClearer = defaultEventsClearer(app),
                    enrollmentFactory = { onCapture ->
                        FaceEnrollmentCoordinator(
                            store = KnownFaceStore(app),
                            embedder = enrollmentEmbedder,
                            faceFinder = FaceEnrollmentCoordinator.busFinder(
                                engineFactory = { MediaPipeFaceEngine(app) },
                            ),
                            settingsLoader = {
                                SettingsStore(app, EncryptedSecretStore(app)).load()
                            },
                            settingsSaver = {
                                SettingsStore(app, EncryptedSecretStore(app)).save(it)
                            },
                            onCapture = onCapture,
                        )
                    },
                )
            }
        }
    }
}
