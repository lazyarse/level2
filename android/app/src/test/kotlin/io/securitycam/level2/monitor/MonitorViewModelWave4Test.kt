package io.securitycam.level2.monitor

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Wave 4: honest startup — Starting until the bind/health confirms, Error otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorViewModelWave4Test {

    private fun viewModel(
        healthy: Boolean = true,
        startRan: MutableList<Int> = mutableListOf(),
        stopRan: MutableList<Int> = mutableListOf(),
        settingsGate: CompletableDeferred<AppSettings> = CompletableDeferred(),
        startMonitoring: (
            String, Boolean, Boolean, String, Boolean, Boolean, String, String,
        ) -> Unit = { _, _, _, _, _, _, _, _ -> startRan.add(1) },
    ) = MonitorViewModel(
        application = ApplicationProvider.getApplicationContext(),
        permissionsGranted = { true },
        startMonitoring = startMonitoring,
        stopMonitoring = { stopRan.add(1) },
        // Gated loader: the coroutine suspends until the test releases it, so
        // the intermediate Starting state is observable deterministically
        // (an inline loader would settle to Monitoring before the assert).
        settingsLoader = { settingsGate.await() },
        scheduleCheckInterval = null,
        surfaceRuntimeStartFailures = false,
        serviceHealth = { healthy },
    ) to settingsGate

    @Test
    fun startStaysStartingUntilHealthyBindConfirms() {
        val (vm, gate) = viewModel(healthy = true)
        vm.start()
        assertEquals(MonitorState.Starting, vm.state.value)
        gate.complete(AppSettings.defaults())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        vm.stop()
    }

    @Test
    fun startWithDeadBindSurfacesErrorInsteadOfMonitoring() {
        val startRan = mutableListOf<Int>()
        val (vm, gate) = viewModel(healthy = false, startRan = startRan)
        vm.start()
        assertEquals(MonitorState.Starting, vm.state.value)
        gate.complete(AppSettings.defaults())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MonitorState.Error, vm.state.value)
        assertTrue(vm.error.value!!.isNotEmpty())
        // The service intent still fired; only the state is honest.
        assertEquals(1, startRan.size)
        vm.stop()
    }

    @Test
    fun startMonitoringThrowSurfacesErrorSynchronously() {
        val (vm, _) = viewModel(
            startMonitoring = { _, _, _, _, _, _, _, _ ->
                throw IllegalStateException("bind exploded")
            },
        )
        vm.start()
        assertEquals(MonitorState.Error, vm.state.value)
        assertTrue(vm.error.value!!.contains("bind exploded"))
    }

    @Test
    fun stopWhileStartingReturnsToIdle() {
        val stopRan = mutableListOf<Int>()
        val (vm, gate) = viewModel(healthy = true, stopRan = stopRan)
        vm.start()
        assertEquals(MonitorState.Starting, vm.state.value)
        vm.stop()
        assertEquals(MonitorState.Idle, vm.state.value)
        assertEquals(1, stopRan.size)
        // Release the gate so the abandoned start settles instead of leaking.
        gate.complete(AppSettings.defaults())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MonitorState.Idle, vm.state.value)
    }
}
