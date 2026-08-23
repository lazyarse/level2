package io.securitycam.level1.ui.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.face.FaceDetection
import io.securitycam.level1.identity.FaceEnrollmentCoordinator
import io.securitycam.level1.identity.KnownFaceStore
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

    private class FakeCoordinator(
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
        assertEquals("Enrolled Bob", vm.message.value)
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
        assertEquals("Enrolled Ann", vm.message.value)
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
        assertEquals("Enrolled Cy", vm.message.value)
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
        assertEquals("Added photo for Bea", vm.message.value)
        assertEquals(listOf(face), vm.draft.value?.knownFaces)
    }

    @Test
    fun successfulEnrollWritesThumbnailFromCaptureHook() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val frame = io.securitycam.level1.detection.ColorBitmap(
            32, 32, ByteArray(3 * 32 * 32) { 0x40 },
        )
        val det = io.securitycam.level1.detection.face.FaceDetection(
            4.0, 4.0, 28.0, 28.0, 0.9,
        )
        val embedder = object : io.securitycam.level1.detection.face.FaceEmbedder {
            override fun embed(f: ColorBitmap, box: DoubleArray): FloatArray =
                floatArrayOf(1f, 0f)
        }
        lateinit var hook: (ColorBitmap, FaceDetection) -> Unit
        val realCoordinator = FaceEnrollmentCoordinator(
            store = KnownFaceStore(createTempDir()),
            embedder = embedder,
            faceFinder = { frame to det },
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            onCapture = { f, d -> hook(f, d) },
        )
        val session = CameraSession()
        val vm = SettingsViewModel(
            application = app,
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            enrollmentFactory = { onCapture ->
                hook = onCapture
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

        assertEquals("Enrolled Tee", vm.message.value)
        val face = vm.draft.value?.knownFaces?.single()
        assertTrue(face != null)
        val thumb = java.io.File(
            java.io.File(app.filesDir, io.securitycam.level1.identity.KnownFaceStore.DIR_NAME),
            "${face!!.id}.jpg",
        )
        assertTrue(thumb.exists() && thumb.length() > 0)
    }

    private companion object {
        fun createTempDir(): File =
            java.nio.file.Files.createTempDirectory("faces").toFile()
    }
}
