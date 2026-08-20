package io.securitycam.level1.monitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
}