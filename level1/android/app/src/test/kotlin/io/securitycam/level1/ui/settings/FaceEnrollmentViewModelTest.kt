package io.securitycam.level1.ui.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.KnownFace
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
    fun enrollmentWithoutCameraPermission_reportsInsteadOfStarting() {
        val coordinator = FakeCoordinator(Result.success(KnownFace(id = "f", label = "Fay")))
        val session = CameraSession()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SettingsViewModel(
            application = app,
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

    private companion object {
        fun createTempDir(): File =
            java.nio.file.Files.createTempDirectory("faces").toFile()
    }
}
