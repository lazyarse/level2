package io.securitycam.level2.monitor

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.DetectionSpeed
import io.securitycam.level2.core.ScheduleWindow
import io.securitycam.level2.detection.DetectionZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorViewModelTest {

    private fun viewModel(
        granted: Boolean = true,
        startRan: MutableList<Int> = mutableListOf(),
        stopRan: MutableList<Int> = mutableListOf(),
    ) = MonitorViewModel(
        application = ApplicationProvider.getApplicationContext(),
        permissionsGranted = { granted },
        startMonitoring = { _, _, _, _, _, _, _, _ -> startRan.add(1) },
        stopMonitoring = { stopRan.add(1) },
        // In-memory loader: settles deterministically without DataStore IO.
        // (The async Starting→Monitoring proof lives in MonitorViewModelWave4Test
        // with a gated loader; init-path async loading is covered below.)
        settingsLoader = { AppSettings.defaults() },
        scheduleCheckInterval = null,
        // Robolectric cannot initialize native detectors; runtime-init failures
        // are environmental here, not product bugs.
        surfaceRuntimeStartFailures = false,
        // Wave 4: the service bind is confirmed asynchronously; tests simulate
        // a healthy bind so start() settles on Monitoring after the looper pumps.
        serviceHealth = { true },
    )

    private fun MonitorViewModel.awaitSettled(timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state.value == MonitorState.Starting && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun start_whenPermissionsGranted_transitionsToMonitoring() {
        val vm = viewModel()
        vm.start()
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)
    }

    @Test
    fun start_whenPermissionsDenied_setsErrorAndDoesNotStart() {
        val startRan = mutableListOf<Int>()
        val vm = viewModel(granted = false, startRan = startRan)
        vm.start()
        assertEquals(MonitorState.Error, vm.state.value)
        assertTrue(vm.error.value!!.isNotEmpty())
        assertTrue(startRan.isEmpty())
    }

    @Test
    fun onPermissionsDenied_setsErrorState() {
        val vm = viewModel()
        vm.onPermissionsDenied()
        assertEquals(MonitorState.Error, vm.state.value)
        assertTrue(vm.error.value!!.isNotEmpty())
    }

    @Test
    fun start_whenAlreadyMonitoring_isNoOp() {
        val startRan = mutableListOf<Int>()
        val vm = viewModel(startRan = startRan)
        vm.start()
        vm.start()
        assertEquals(1, startRan.size)
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)
    }

    @Test
    fun stop_transitionsToIdleAndInvokesStop() {
        val stopRan = mutableListOf<Int>()
        val vm = viewModel(stopRan = stopRan)
        vm.start()
        vm.awaitSettled()
        vm.stop()
        assertEquals(MonitorState.Idle, vm.state.value)
        assertEquals(1, stopRan.size)
    }

    @Test
    fun requiredPermissions_includesCameraAudio() {
        val vm = viewModel()
        val perms = vm.requiredPermissions()
        assertTrue(perms.contains(android.Manifest.permission.CAMERA))
        assertTrue(perms.contains(android.Manifest.permission.RECORD_AUDIO))
    }

    // ---- Schedule enforcement (design: 2026-08-19-monitoring-schedule) ----

    private fun scheduleSettings(always: Boolean): AppSettings = AppSettings(
        detectorConfigs = AppSettings.defaults().detectorConfigs,
        channelConfigs = AppSettings.defaults().channelConfigs,
        scheduleExclusions = if (!always) {
            emptyList()
        } else {
            listOf(
                // Mon–Sun 00:00–00:00 ⇒ a 24 h exclusion.
                ScheduleWindow(id = "w1", days = 0b1111111, startHour = 0, startMinute = 0, endHour = 0, endMinute = 0),
            )
        },
    )

    @Test
    fun autoStopWhenEnteringExclusion_andResumeWhenLeaving() {
        var excluded = false
        val startRan = mutableListOf<Int>()
        val stopRan = mutableListOf<Int>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> startRan.add(1) },
            stopMonitoring = { stopRan.add(1) },
            settingsLoader = { scheduleSettings(always = excluded) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        vm.start()
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)

        kotlinx.coroutines.runBlocking {
            excluded = true
            vm.checkScheduleNow()
        }
        assertEquals(MonitorState.Idle, vm.state.value)
        assertTrue(vm.schedulePaused.value)
        assertEquals(1, stopRan.size)

        kotlinx.coroutines.runBlocking {
            excluded = false
            vm.checkScheduleNow()
        }
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        assertTrue(!vm.schedulePaused.value)
        assertEquals(2, startRan.size)
    }

    @Test
    fun manualStartBlockedWhileExcluded() {
        val startRan = mutableListOf<Int>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> startRan.add(1) },
            stopMonitoring = {},
            settingsLoader = { scheduleSettings(always = true) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
        )
        // Prime the cached settings (as the periodic tick would).
        kotlinx.coroutines.runBlocking { vm.checkScheduleNow() }
        vm.start()
        assertEquals(MonitorState.Idle, vm.state.value)
        assertTrue(startRan.isEmpty())
        assertTrue(vm.scheduleNote.value!!.contains("scheduled exclusion"))
    }

    @Test
    fun togglePreviewPersistsAndRebindsWhileMonitoring() = runBlocking {
        val rebinds = mutableListOf<Boolean>()
        val saved = mutableListOf<AppSettings>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = { saved.add(it) },
            previewRebind = { rebinds.add(it) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        // Preview ships off (battery saver); the persisted default agrees.
        assertFalse(vm.monitorPreview.value)

        vm.togglePreview()
        assertTrue(vm.monitorPreview.value)
        // Not monitoring yet → no rebind, but choice is persisted.
        assertTrue(rebinds.isEmpty())
        assertEquals(true, saved.single().monitorPreview)

        vm.start()
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        vm.togglePreview()
        // Monitoring → rebind fired with the new value; persisted again.
        assertEquals(listOf(false), rebinds)
        assertFalse(vm.monitorPreview.value)
        assertEquals(2, saved.size)
        assertEquals(false, saved.last().monitorPreview)
    }

    @Test
    fun refreshSettingsDoesNotResetSessionPreviewToggle() {
        val saved = mutableListOf<AppSettings>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            // The persisted default stays false throughout — simulating a disk
            // value that hasn't caught up with the session toggle yet.
            settingsLoader = { AppSettings.defaults().copy(monitorPreview = false) },
            settingsSaver = { saved.add(it) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.monitorPreview.value)

        vm.togglePreview()
        assertEquals(true, vm.monitorPreview.value)
        assertEquals(true, saved.single().monitorPreview)

        // A resume-time reload (returning from Events/Settings) reads stale
        // persisted state; the session toggle must win, not get blacked out.
        vm.refreshSettings()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, vm.monitorPreview.value)
    }

    @Test
    fun reattachPreview_rebindsOnlyWhenMonitoringWithFeedOn() {
        val rebinds = mutableListOf<Boolean>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { AppSettings.defaults() },
            previewRebind = { rebinds.add(it) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        shadowOf(Looper.getMainLooper()).idle()

        // Idle: no-op even with the feed toggled on.
        vm.togglePreview()
        assertEquals(true, vm.monitorPreview.value)
        vm.reattachPreview()
        assertTrue(rebinds.isEmpty())

        vm.start()
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)

        // Monitoring with the feed off: no-op (the toggle itself rebinds).
        vm.togglePreview()
        assertEquals(listOf(false), rebinds)
        vm.reattachPreview()
        assertEquals(listOf(false), rebinds)

        // Monitoring with the feed on: rebinds to resume the fresh surface.
        vm.togglePreview()
        assertEquals(listOf(false, true), rebinds)
        vm.reattachPreview()
        assertEquals(listOf(false, true, true), rebinds)
    }

    @Test
    fun manualStopClearsPendingAutoResume() {
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { scheduleSettings(always = true) },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        vm.start()
        vm.awaitSettled()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        kotlinx.coroutines.runBlocking { vm.checkScheduleNow() }
        assertTrue(vm.schedulePaused.value)
        vm.stop()
        assertTrue(!vm.schedulePaused.value)
    }

    @Test
    fun startLoadsBothZoneListsIntoFlows() {
        val inclusion = listOf(
            DetectionZone("r1", "rect", "doorway", listOf(0.1, 0.2, 0.5, 0.8)),
        )
        val exclusions = listOf(
            DetectionZone("e1", "rect", "private", listOf(0.6, 0.6, 0.9, 0.9)),
        )
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = {
                AppSettings.defaults().copyWith(
                    detectionZones = inclusion,
                    exclusionZones = exclusions,
                )
            },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(exclusions, vm.exclusionZones.value)
        assertEquals(inclusion, vm.detectionZones.value)
        vm.start()
        // Pump the main-looper coroutine; runtime creation may fail under
        // Robolectric (no native MediaPipe) but is swallowed after the flows
        // are populated.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(inclusion, vm.detectionZones.value)
        assertEquals(exclusions, vm.exclusionZones.value)
    }

    @Test
    fun initLoadsSettingsAsynchronously() {
        val gate = kotlinx.coroutines.CompletableDeferred<AppSettings>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { gate.await() },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
        )
        // Loader still gated: placeholders, no main-thread block.
        assertEquals("Hallway", vm.cameraName.value)

        gate.complete(AppSettings.defaults().copy(cameraName = "Porch"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Porch", vm.cameraName.value)
    }

    @Test
    fun speedNudgeHiddenByDefault() {
        assertFalse(viewModel().showSpeedNudge.value)
    }

    @Test
    fun speedNudgeCondition() {
        val accuracy = AppSettings.defaults()
        val balanced = accuracy.copy(detectionSpeed = DetectionSpeed.balanced)
        // Slow phone, fresh session, Best accuracy → show.
        assertTrue(shouldShowSpeedNudge(true, accuracy, snoozed = false))
        // Fast phone → never.
        assertFalse(shouldShowSpeedNudge(false, accuracy, snoozed = false))
        // Snoozed this session → hidden even when slow.
        assertFalse(shouldShowSpeedNudge(true, accuracy, snoozed = true))
        // Already off Best accuracy → nothing to suggest.
        assertFalse(shouldShowSpeedNudge(true, balanced, snoozed = false))
    }

    @Test
    fun dismissSpeedNudge_hidesBannerWithoutSaving() {
        val saved = mutableListOf<AppSettings>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = { saved.add(it) },
            scheduleCheckInterval = null,
        )
        vm.dismissSpeedNudge()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(saved.isEmpty())
        assertFalse(vm.showSpeedNudge.value)
    }
}