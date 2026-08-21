package io.securitycam.level1.monitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        startMonitoring = { startRan.add(1) },
        stopMonitoring = { stopRan.add(1) },
    )

    @Test
    fun start_whenPermissionsGranted_transitionsToMonitoring() {
        val vm = viewModel()
        vm.start()
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
        assertEquals(MonitorState.Monitoring, vm.state.value)
    }

    @Test
    fun stop_transitionsToIdleAndInvokesStop() {
        val stopRan = mutableListOf<Int>()
        val vm = viewModel(stopRan = stopRan)
        vm.start()
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
            startMonitoring = { startRan.add(1) },
            stopMonitoring = { stopRan.add(1) },
            settingsLoader = { scheduleSettings(always = excluded) },
            scheduleCheckInterval = null,
        )
        vm.start()
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
            startMonitoring = { startRan.add(1) },
            stopMonitoring = {},
            settingsLoader = { scheduleSettings(always = true) },
            scheduleCheckInterval = null,
        )
        // Prime the cached settings (as the periodic tick would).
        kotlinx.coroutines.runBlocking { vm.checkScheduleNow() }
        vm.start()
        assertEquals(MonitorState.Idle, vm.state.value)
        assertTrue(startRan.isEmpty())
        assertTrue(vm.scheduleNote.value!!.contains("scheduled exclusion"))
    }

    @Test
    fun manualStopClearsPendingAutoResume() {
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = {},
            stopMonitoring = {},
            settingsLoader = { scheduleSettings(always = true) },
            scheduleCheckInterval = null,
        )
        vm.start()
        kotlinx.coroutines.runBlocking { vm.checkScheduleNow() }
        assertTrue(vm.schedulePaused.value)
        vm.stop()
        assertTrue(!vm.schedulePaused.value)
    }
}