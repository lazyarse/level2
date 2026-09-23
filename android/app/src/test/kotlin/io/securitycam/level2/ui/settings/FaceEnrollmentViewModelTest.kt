package io.securitycam.level2.ui.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.identity.FaceEnrollmentCoordinator
import io.securitycam.level2.identity.FaceFinder
import io.securitycam.level2.identity.KnownFaceStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [SettingsViewModel.startEnrollment]'s camera-session
 * orchestration: a temporary preview-only session is started when no camera is
 * active and stopped afterwards; an already-active session is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceEnrollmentViewModelTest {

    private open class FakeCoordinator(
        private val result: Result<KnownFace>,
        private val onAddSample: ((String) -> Result<KnownFace>)? = null,
    ) : FaceEnrollmentCoordinator(
        store = KnownFaceStore(createTempDir()),
        embedder = null,
        faceFinder = { null },
        settingsLoader = { AppSettings.defaults() },
        settingsSaver = {},
    ) {
        var enrolledLabels: MutableList<String> = mutableListOf()

        override suspend fun enroll(label: String): Result<KnownFace> {
            enrolledLabels.add(label)
            return result
        }

        override suspend fun addSample(id: String): Result<KnownFace> =
            onAddSample?.invoke(id) ?: super.addSample(id)
    }

    /** enroll() that never returns — models waiting on the frame bus. */
    private class HangingCoordinator : FaceEnrollmentCoordinator(
        store = KnownFaceStore(createTempDir()),
        embedder = null,
        faceFinder = { null },
        settingsLoader = { AppSettings.defaults() },
        settingsSaver = {},
    ) {
        var enrollCalls: Int = 0

        override suspend fun enroll(label: String): Result<KnownFace> {
            enrollCalls++
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private class CameraSession {
        var active: Boolean = false
        var startedIds: MutableList<String> = mutableListOf()
        var startCount: Int = 0
        var stopCount: Int = 0
        var lastSwitch: String? = null

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Models the async service start/bind: active only after pumping. */
        fun bindLater() {
            handler.postDelayed({ active = true }, 50)
        }
    }

    private fun viewModel(
        coordinator: FaceEnrollmentCoordinator,
        session: CameraSession,
        initiallyActive: Boolean = false,
        switchCalls: MutableList<String>? = null,
    ): SettingsViewModel = SettingsViewModel(
        settingsLoader = { AppSettings.defaults() },
        settingsSaver = {},
        eventsClearer = {},
        enrollmentFactory = { _ -> coordinator },
        cameraActive = { session.active },
        startCameraSession = { cameraId ->
            session.startCount++
            session.startedIds.add(cameraId)
            session.bindLater()
        },
        stopCameraSession = {
            session.stopCount++
            session.active = false
        },
        switchPreviewCamera = { cameraId ->
            // Mirror controller semantics: needs a live session, de-dups.
            if (session.active && session.lastSwitch != cameraId) {
                switchCalls?.add(cameraId)
                session.lastSwitch = cameraId
            }
        },
        framesWaitTimeoutMs = 200,
        framesSettleMs = 10,
    ).also { require(!initiallyActive || session.active) }

    /** Drives the Main-looper coroutine (including its delays) to completion. */
    private fun pumpUntilIdle(vm: SettingsViewModel) {
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.enrollingLabel.value != null && tries++ < 100) {
            looper.runToEndOfTasks()
        }
        looper.runToEndOfTasks()
    }

    /** Pumps the main looper until [condition] holds (or the try budget runs out). */
    private fun pumpUntil(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (!condition() && tries++ < 200) {
            looper.runToEndOfTasks()
        }
    }

    private class PhaseEmbedder : io.securitycam.level2.detection.face.FaceEmbedder {
        override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray =
            floatArrayOf(1f, 0f)
    }

    /**
     * Real coordinator whose finder awaits the shutter hook, with the VM's
     * confirm/onNoFace hooks wired through — the production phase machine.
     * [findFace] decides each capture's outcome (null = no face).
     */
    private fun phaseViewModel(
        session: CameraSession,
        findFace: (call: Int) -> Pair<ColorBitmap, FaceDetection>?,
    ): SettingsViewModel {
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        var calls = 0
        return SettingsViewModel(
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { hooks ->
                FaceEnrollmentCoordinator(
                    store = KnownFaceStore(createTempDir()),
                    embedder = PhaseEmbedder(),
                    faceFinder = FaceFinder {
                        hooks.awaitShutter()
                        findFace(++calls)
                    },
                    settingsLoader = { AppSettings.defaults() },
                    settingsSaver = {},
                    onCapture = hooks.onCapture,
                    confirm = hooks.confirm,
                    onNoFace = hooks.onNoFace,
                    onEnrolled = hooks.onEnrolled,
                )
            },
            cameraActive = { session.active },
            startCameraSession = { _ ->
                session.startCount++
                session.bindLater()
            },
            stopCameraSession = {
                session.stopCount++
                session.active = false
            },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
    }

    @Test
    fun shutterArmCaptureUse_completesEnrollment() {
        val session = CameraSession()
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        val vm = phaseViewModel(session) { frame to det }

        vm.startEnrollment("Bob")
        pumpUntil { vm.shutterArmed.value }
        assertTrue(vm.shutterArmed.value)
        assertEquals(null, vm.capturedFrame.value)

        vm.requestCapture()
        pumpUntil { vm.capturedFrame.value != null }
        assertTrue(vm.capturedFrame.value != null)
        assertEquals(false, vm.shutterArmed.value)

        vm.useCapturedPhoto()
        pumpUntilIdle(vm)

        assertEquals(null, vm.enrollingLabel.value)
        assertEquals(null, vm.capturedFrame.value)
        assertEquals(false, vm.shutterArmed.value)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Bob" + suffix, vm.message.value)
    }

    @Test
    fun retake_rearmsShutter_thenUseSucceeds() {
        val session = CameraSession()
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        var calls = 0
        val vm = phaseViewModel(session) { calls++; frame to det }

        vm.startEnrollment("Cara")
        pumpUntil { vm.shutterArmed.value }
        vm.requestCapture()
        pumpUntil { vm.capturedFrame.value != null }

        vm.retakeCapturedPhoto()
        pumpUntil { vm.capturedFrame.value == null && vm.shutterArmed.value }
        assertTrue(vm.shutterArmed.value)

        vm.requestCapture()
        pumpUntil { vm.capturedFrame.value != null }
        vm.useCapturedPhoto()
        pumpUntilIdle(vm)

        assertEquals(2, calls)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Cara" + suffix, vm.message.value)
    }

    @Test
    fun noFace_setsInlineError_andRearms() {
        val session = CameraSession()
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        var lastCall = 0
        val vm = phaseViewModel(session) { n ->
            lastCall = n
            if (n == 1) null else frame to det
        }

        vm.startEnrollment("Dee")
        pumpUntil { vm.shutterArmed.value }
        vm.requestCapture()
        pumpUntil {
            vm.enrollmentError.value != null || vm.capturedFrame.value != null
        }
        assertEquals("No face detected — try again", vm.enrollmentError.value)
        assertEquals(null, vm.capturedFrame.value)
        // Interactive mode re-arms for the next press after the miss.
        pumpUntil { vm.shutterArmed.value }
        assertTrue(vm.shutterArmed.value)

        vm.requestCapture()
        pumpUntil { vm.capturedFrame.value != null }
        assertEquals(null, vm.enrollmentError.value) // cleared by the press
        vm.useCapturedPhoto()
        pumpUntilIdle(vm)

        assertEquals(2, lastCall)
        assertTrue(vm.message.value?.contains("Enrolled Dee") == true)
        assertEquals(null, vm.enrollmentError.value)
    }

    @Test
    fun requestCapture_beforeArmed_isDropped() {
        val session = CameraSession()
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        val vm = phaseViewModel(session) { frame to det }

        vm.startEnrollment("Fay")
        // Press before the finder parks: gate is null, nothing happens.
        vm.requestCapture()
        assertEquals(null, vm.capturedFrame.value)

        pumpUntil { vm.shutterArmed.value }
        assertTrue(vm.shutterArmed.value)
        assertEquals(null, vm.capturedFrame.value)
        vm.cancelEnrollment()
        pumpUntilIdle(vm)
        assertEquals(null, vm.enrollingLabel.value)
    }

    @Test
    fun cancelDuringReview_stopsSessionAndClearsState() {
        val session = CameraSession()
        val frame = ColorBitmap(8, 8, ByteArray(3 * 8 * 8))
        val det = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        val vm = phaseViewModel(session) { frame to det }

        vm.startEnrollment("Gus")
        pumpUntil { vm.shutterArmed.value }
        vm.requestCapture()
        pumpUntil { vm.capturedFrame.value != null }

        vm.cancelEnrollment()
        pumpUntilIdle(vm)

        assertEquals(null, vm.enrollingLabel.value)
        assertEquals(null, vm.capturedFrame.value)
        assertEquals(false, vm.shutterArmed.value)
        assertEquals(1, session.stopCount)
        assertEquals("Enrollment cancelled", vm.message.value)
    }

    @Test
    fun cancelDuringEnrollment_stopsSessionAndClearsState() {
        val coordinator = HangingCoordinator()
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startEnrollment("Bea")
        // Pump until the coroutine is parked inside the hanging enroll().
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (coordinator.enrollCalls == 0 && tries++ < 100) {
            looper.runToEndOfTasks()
        }
        assertTrue(coordinator.enrollCalls > 0)
        assertTrue(session.active)

        vm.cancelEnrollment()
        pumpUntilIdle(vm)

        assertEquals(false, session.active)
        assertEquals(1, session.stopCount)
        assertEquals(null, vm.enrollingLabel.value)
        assertEquals("Enrollment cancelled", vm.message.value)
    }

    @Test
    fun enrollmentWithoutCameraSession_startsAndStopsAroundEnroll() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "face_x", label = "Bob")))
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startEnrollment("Bob")
        pumpUntilIdle(vm)

        assertEquals(1, session.startCount)
        assertEquals(listOf("0"), session.startedIds)
        assertEquals(1, session.stopCount)
        assertEquals(false, session.active)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Bob" + suffix, vm.message.value)
        assertEquals(null, vm.enrollingLabel.value)
        assertEquals(listOf("Bob"), coordinator.enrolledLabels)
    }

    @Test
    fun enrollmentWithCameraAlreadyActive_leavesSessionAlone() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Ann")))
        val session = CameraSession().apply { active = true }
        val vm = viewModel(coordinator, session, initiallyActive = true)

        vm.startEnrollment("Ann")
        pumpUntilIdle(vm)

        assertEquals(0, session.startCount)
        assertEquals(0, session.stopCount)
        assertTrue(session.active)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Ann" + suffix, vm.message.value)
    }

    @Test
    fun enrollmentFailure_stillStopsSessionAndSurfacesMessage() {
        val coordinator = FakeCoordinator(Result.failure(IllegalStateException("No face seen")))
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startEnrollment("Sid")
        pumpUntilIdle(vm)

        assertEquals(1, session.stopCount)
        assertEquals(false, session.active)
        assertEquals("Enroll failed: No face seen", vm.message.value)
        assertEquals(null, vm.enrollingLabel.value)
    }

    @Test
    fun cameraFailingToStart_reportsErrorAndStopsSession() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Jo")))
        val session = CameraSession()
        // startCameraSession that never becomes active:
        val vm = SettingsViewModel(
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { coordinator },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.startCount++ },
            stopCameraSession = { session.stopCount++ },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )

        vm.startEnrollment("Jo")
        pumpUntilIdle(vm)

        assertEquals("Enroll failed: Camera did not start", vm.message.value)
        assertEquals(0, coordinator.enrolledLabels.size)
        assertEquals(1, session.stopCount)
        assertEquals(null, vm.enrollingLabel.value)
    }

    @Test
    fun concurrentEnrollmentRequestsAreIgnored() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Al")))
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startEnrollment("Al")
        vm.startEnrollment("Al")
        pumpUntilIdle(vm)

        assertEquals(listOf("Al"), coordinator.enrolledLabels)
    }

    @Test
    fun flipBeforeSessionBound_switchesToFrontAfterBind() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Cy")))
        val session = CameraSession()
        val switches = mutableListOf<String>()
        val vm = viewModel(coordinator, session, switchCalls = switches)

        vm.startEnrollment("Cy")
        // Flip during the bind wait (session not active yet): the controller
        // drops it, and the post-wait heal applies "front".
        vm.flipEnrollmentCamera()
        pumpUntilIdle(vm)

        assertEquals(listOf("front"), switches)
        assertEquals("front", session.lastSwitch)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Cy" + suffix, vm.message.value)
    }

    @Test
    fun flipFromFrontBase_togglesToBack() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Dee")))
        val session = CameraSession()
        val switches = mutableListOf<String>()
        val vm = SettingsViewModel(
            settingsLoader = { AppSettings.defaults().copyWith(cameraId = "1") },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { coordinator },
            cameraActive = { session.active },
            startCameraSession = { cameraId ->
                session.startCount++
                session.startedIds.add(cameraId)
                session.bindLater()
            },
            stopCameraSession = {
                session.stopCount++
                session.active = false
            },
            switchPreviewCamera = { cameraId ->
                if (session.active && session.lastSwitch != cameraId) {
                    switches.add(cameraId)
                    session.lastSwitch = cameraId
                }
            },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )

        vm.startEnrollment("Dee")
        // Persisted camera is the front one ("1"): flipping targets back.
        vm.flipEnrollmentCamera()
        pumpUntilIdle(vm)

        assertEquals(listOf("back"), switches)
        assertEquals("back", session.lastSwitch)
        assertEquals("1", session.startedIds.first())
    }

    @Test
    fun flipIgnoredWhenMonitoringOwnsSession() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Eli")))
        val session = CameraSession().apply { active = true }
        val switches = mutableListOf<String>()
        val vm = viewModel(coordinator, session, initiallyActive = true, switchCalls = switches)

        vm.startEnrollment("Eli")
        pumpUntilIdle(vm)
        vm.flipEnrollmentCamera()

        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun enrollmentWithoutCameraPermission_reportsInsteadOfStarting() {        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Fay")))
        val session = CameraSession()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { _ -> coordinator },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.startCount++ },
            stopCameraSession = { session.stopCount++ },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        // Robolectric grants permissions by default; deny CAMERA explicitly.
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA)

        assertEquals(
            listOf(android.Manifest.permission.CAMERA),
            vm.missingEnrollmentPermissions(),
        )
        vm.startEnrollment("Fay")
        pumpUntilIdle(vm)

        assertEquals(0, session.startCount)
        assertEquals(0, coordinator.enrolledLabels.size)
        assertEquals("Camera permission is required to enrol a face", vm.message.value)
    }

    @Test
    fun enrollSuccessMergesFaceIntoDraft() {
        val face = KnownFace(id = "face_d1", label = "Dana")
        val coordinator = FakeCoordinator(Result.success(face))
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startEnrollment("Dana")
        pumpUntilIdle(vm)

        assertEquals(listOf(face), vm.draft.value?.knownFaces)
    }

    @Test
    fun duplicateNameBlockedWithoutCameraLaunch() {
        val existing = listOf(KnownFace(id = "face_a", label = "Alice"))
        var settings = AppSettings.defaults().copyWith(knownFaces = existing)
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "face_x", label = "x")))
        val session = CameraSession()
        val vm = SettingsViewModel(
            settingsLoader = { settings },
            settingsSaver = { s -> settings = s },
            eventsClearer = {},
            enrollmentFactory = { _ -> coordinator },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.startCount++ },
            stopCameraSession = {},
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        // Wait for the async initial draft load.
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()

        vm.startEnrollment("alice") // case-insensitive duplicate
        pumpUntilIdle(vm)

        assertTrue(vm.message.value?.contains("already enrolled") == true)
        assertEquals(0, session.startCount)
        assertTrue(coordinator.enrolledLabels.isEmpty())
    }

    @Test
    fun stalePersistedEntryAfterUnsavedDelete_doesNotBlockReenrollment() {
        // Persisted settings still hold Alice (deleted from the draft without
        // saving); the draft does not.
        val stale = KnownFace(id = "face_stale", label = "Alice")
        var settings = AppSettings.defaults().copyWith(knownFaces = listOf(stale))
        val fresh = KnownFace(id = "face_new", label = "Alice")
        val coordinator = FakeCoordinator(Result.success(fresh))
        val session = CameraSession()
        val vm = SettingsViewModel(
            settingsLoader = { settings },
            settingsSaver = { s -> settings = s },
            eventsClearer = {},
            enrollmentFactory = { _ -> coordinator },
            cameraActive = { session.active },
            startCameraSession = { _ ->
                session.startCount++
                session.bindLater()
            },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        // Wait for the async initial draft load.
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()
        // Simulate the unsaved delete: draft drops Alice, persisted keeps her.
        vm.update { it.copy(knownFaces = emptyList()) }

        vm.startEnrollment("Alice")
        pumpUntilIdle(vm)

        // The coordinator ran (not rejected) and the stale entry is purged.
        assertEquals(listOf("Alice"), coordinator.enrolledLabels)
        assertTrue(settings.knownFaces.none { it.id == "face_stale" })
        assertEquals(listOf(fresh), vm.draft.value?.knownFaces)
    }

    @Test
    fun failedEnrollmentPurgesPersistedResidueAndFreesTheName() {
        var settings = AppSettings.defaults()
        val residue = KnownFace(id = "face_orphan", label = "Zed")
        val failing = object : FakeCoordinator(
            Result.failure(IllegalStateException("No face seen")),
        ) {
            override suspend fun enroll(label: String): Result<KnownFace> {
                enrolledLabels.add(label)
                // Simulate the partial persist: label saved, then failure.
                settings = settings.copyWith(knownFaces = settings.knownFaces + residue)
                return Result.failure(IllegalStateException("No face seen"))
            }
        }
        var active: FaceEnrollmentCoordinator = failing
        val session = CameraSession()
        val vm = SettingsViewModel(
            settingsLoader = { settings },
            settingsSaver = { s -> settings = s },
            eventsClearer = {},
            enrollmentFactory = { _ -> active },
            cameraActive = { session.active },
            startCameraSession = { _ ->
                session.startCount++
                session.bindLater()
            },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()

        vm.startEnrollment("Zed")
        pumpUntilIdle(vm)

        assertEquals("Enroll failed: No face seen", vm.message.value)
        assertTrue(settings.knownFaces.isEmpty())

        // The name is reusable immediately.
        val fresh = KnownFace(id = "face_new", label = "Zed")
        active = FakeCoordinator(Result.success(fresh))
        vm.startEnrollment("Zed")
        pumpUntilIdle(vm)

        assertTrue(vm.message.value?.contains("Enrolled Zed") == true)
        assertEquals(listOf(fresh), vm.draft.value?.knownFaces)
    }

    @Test
    fun sampleCaptureRoutesToAddSampleAndUpdatesDraft() {
        val face = KnownFace(id = "face_b", label = "Bea")
        var addedId: String? = null
        val coordinator = FakeCoordinator(Result.success(face)) { addedId = it; Result.success(face) }
        val session = CameraSession()
        val vm = viewModel(coordinator, session)

        vm.startSampleCapture(face)
        pumpUntilIdle(vm)

        assertEquals("face_b", addedId)
        assertEquals(1, session.startCount)
        assertEquals(1, session.stopCount)
        assertEquals(false, session.active)
        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Added photo for Bea" + suffix, vm.message.value)
        assertEquals(listOf(face), vm.draft.value?.knownFaces)
    }

    @Test
    fun successfulEnrollWritesThumbnailFromCaptureHook() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val frame = io.securitycam.level2.detection.ColorBitmap(
            32, 32, ByteArray(3 * 32 * 32) { 0x40 },
        )
        val det = io.securitycam.level2.detection.face.FaceDetection(
            0.125, 0.125, 0.875, 0.875, 0.9,
        )
        val embedder = object : io.securitycam.level2.detection.face.FaceEmbedder {
            override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray =
                floatArrayOf(1f, 0f)
        }
        lateinit var hook: (ColorBitmap, FaceDetection) -> Unit
        lateinit var enrolledHook: (String, FloatArray) -> Unit
        val facesDir = java.io.File(
            app.filesDir,
            io.securitycam.level2.identity.KnownFaceStore.DIR_NAME,
        )
        val realCoordinator = FaceEnrollmentCoordinator(
            store = KnownFaceStore(facesDir),
            embedder = embedder,
            faceFinder = { frame to det },
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            onCapture = { f, d -> hook(f, d) },
            onEnrolled = { id, embedding -> enrolledHook(id, embedding) },
        )
        val session = CameraSession()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { hooks ->
                hook = hooks.onCapture
                enrolledHook = hooks.onEnrolled
                realCoordinator
            },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.bindLater() },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )

        vm.startEnrollment("Tee")
        pumpUntilIdle(vm)

        val suffix = " — face recognition enabled; restart monitoring to apply"
        assertEquals("Enrolled Tee" + suffix, vm.message.value)
        val face = vm.draft.value?.knownFaces?.single()
        assertTrue(face != null)
        val photo = java.io.File(facesDir, "${face!!.id}_0.jpg")
        assertTrue(photo.exists() && photo.length() > 0)
        assertEquals(listOf(photo), vm.listFacePhotos(face.id))
        assertEquals(1, vm.facePhotoCount(face.id))
        assertEquals(1, KnownFaceStore(facesDir).sampleCount(face.id))
    }

    @Test
    fun secondSampleWritesNextIndexAndJournalsBoth() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val frame = io.securitycam.level2.detection.ColorBitmap(
            32, 32, ByteArray(3 * 32 * 32) { 0x40 },
        )
        val det = io.securitycam.level2.detection.face.FaceDetection(
            0.125, 0.125, 0.875, 0.875, 0.9,
        )
        val embedder = object : io.securitycam.level2.detection.face.FaceEmbedder {
            override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray =
                floatArrayOf(1f, 0f)
        }
        val facesDir = java.io.File(
            app.filesDir,
            io.securitycam.level2.identity.KnownFaceStore.DIR_NAME,
        )
        lateinit var hooks: EnrollmentHooks
        // Stateful so addSample finds the face the coordinator itself saved.
        var coordSettings = AppSettings.defaults()
        val realCoordinator = FaceEnrollmentCoordinator(
            store = KnownFaceStore(facesDir),
            embedder = embedder,
            faceFinder = { frame to det },
            settingsLoader = { coordSettings },
            settingsSaver = { coordSettings = it },
            onCapture = { f, d -> hooks.onCapture(f, d) },
            onEnrolled = { id, embedding -> hooks.onEnrolled(id, embedding) },
        )
        val session = CameraSession()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { h ->
                hooks = h
                realCoordinator
            },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.bindLater() },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()

        vm.startEnrollment("Tee")
        pumpUntilIdle(vm)
        val face = vm.draft.value?.knownFaces?.single()!!
        session.active = false

        vm.startSampleCapture(face)
        pumpUntilIdle(vm)

        assertEquals("Added photo for Tee", vm.message.value?.substringBefore(" —"))
        assertEquals(2, vm.facePhotoCount(face.id))
        assertTrue(java.io.File(facesDir, "${face.id}_0.jpg").exists())
        assertTrue(java.io.File(facesDir, "${face.id}_1.jpg").exists())
        assertTrue(java.io.File(facesDir, "${face.id}.smp").exists())
        assertEquals(2, KnownFaceStore(facesDir).sampleCount(face.id))
    }

    @Test
    fun deleteFacePhotoRemovesPhotoAndUnlearns() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val frame = io.securitycam.level2.detection.ColorBitmap(
            32, 32, ByteArray(3 * 32 * 32) { 0x40 },
        )
        val det = io.securitycam.level2.detection.face.FaceDetection(
            0.125, 0.125, 0.875, 0.875, 0.9,
        )
        var embedCount = 0
        val embedder = object : io.securitycam.level2.detection.face.FaceEmbedder {
            override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray {
                embedCount++
                return floatArrayOf(embedCount.toFloat(), 0f)
            }
        }
        val facesDir = java.io.File(
            app.filesDir,
            io.securitycam.level2.identity.KnownFaceStore.DIR_NAME,
        )
        lateinit var hooks: EnrollmentHooks
        // Stateful so addSample finds the face the coordinator itself saved.
        var coordSettings = AppSettings.defaults()
        val realCoordinator = FaceEnrollmentCoordinator(
            store = KnownFaceStore(facesDir),
            embedder = embedder,
            faceFinder = { frame to det },
            settingsLoader = { coordSettings },
            settingsSaver = { coordSettings = it },
            onCapture = { f, d -> hooks.onCapture(f, d) },
            onEnrolled = { id, embedding -> hooks.onEnrolled(id, embedding) },
        )
        val session = CameraSession()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { h ->
                hooks = h
                realCoordinator
            },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.bindLater() },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()

        vm.startEnrollment("Tee")
        pumpUntilIdle(vm)
        val face = vm.draft.value?.knownFaces?.single()!!
        session.active = false
        vm.startSampleCapture(face)
        pumpUntilIdle(vm)
        assertEquals(2, vm.facePhotoCount(face.id))

        vm.deleteFacePhoto(face, 1)
        pumpUntilIdle(vm)

        assertEquals("Photo removed", vm.message.value)
        assertEquals(1, vm.facePhotoCount(face.id))
        assertTrue(java.io.File(facesDir, "${face.id}_0.jpg").exists())
        assertTrue(!java.io.File(facesDir, "${face.id}_1.jpg").exists())
        val remaining = KnownFaceStore(facesDir).load(face.id)!!
        // Only the first sample (1, 0) survives, normalized.
        assertEquals(1.0, remaining[0].toDouble(), 1e-6)
        assertEquals(0.0, remaining[1].toDouble(), 1e-6)
    }

    @Test
    fun deleteFacePhotoRefusesLastPhoto() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val frame = io.securitycam.level2.detection.ColorBitmap(
            32, 32, ByteArray(3 * 32 * 32) { 0x40 },
        )
        val det = io.securitycam.level2.detection.face.FaceDetection(
            0.125, 0.125, 0.875, 0.875, 0.9,
        )
        val embedder = object : io.securitycam.level2.detection.face.FaceEmbedder {
            override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray =
                floatArrayOf(1f, 0f)
        }
        val facesDir = java.io.File(
            app.filesDir,
            io.securitycam.level2.identity.KnownFaceStore.DIR_NAME,
        )
        lateinit var hooks: EnrollmentHooks
        // Stateful so addSample finds the face the coordinator itself saved.
        var coordSettings = AppSettings.defaults()
        val realCoordinator = FaceEnrollmentCoordinator(
            store = KnownFaceStore(facesDir),
            embedder = embedder,
            faceFinder = { frame to det },
            settingsLoader = { coordSettings },
            settingsSaver = { coordSettings = it },
            onCapture = { f, d -> hooks.onCapture(f, d) },
            onEnrolled = { id, embedding -> hooks.onEnrolled(id, embedding) },
        )
        val session = CameraSession()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { h ->
                hooks = h
                realCoordinator
            },
            cameraActive = { session.active },
            startCameraSession = { _ -> session.bindLater() },
            stopCameraSession = { session.active = false },
            framesWaitTimeoutMs = 200,
            framesSettleMs = 10,
        )
        val looper = shadowOf(Looper.getMainLooper())
        var tries = 0
        while (vm.draft.value == null && tries++ < 100) looper.runToEndOfTasks()

        vm.startEnrollment("Tee")
        pumpUntilIdle(vm)
        val face = vm.draft.value?.knownFaces?.single()!!

        vm.deleteFacePhoto(face, 0)
        pumpUntilIdle(vm)

        assertEquals("Cannot remove the last photo", vm.message.value)
        assertEquals(1, vm.facePhotoCount(face.id))
        assertTrue(java.io.File(facesDir, "${face.id}_0.jpg").exists())
    }

    private companion object {
        fun createTempDir(): File =
            java.nio.file.Files.createTempDirectory("faces").toFile()
    }
}
