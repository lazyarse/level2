package io.securitycam.level2.monitor

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Wave 2: cycleCamera surfaces failures; start re-sends stale FGS params. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorViewModelWave2Test {

    @Test
    fun cycleCameraFailureSurfacesErrorInsteadOfEscaping() {
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { throw IllegalStateException("disk gone") },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
        )
        vm.cycleCamera()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.error.value!!.contains("Switching camera failed"))
    }

    @Test
    fun startRefreshesStaleRecordingParamsAfterSettingsLoad() {
        val refreshed = mutableListOf<String>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            // Fresh settings differ from the (empty) cache start() sends with.
            settingsLoader = {
                AppSettings.defaults().copy(cameraName = "Porch", preRollSeconds = 9)
            },
            recordingParamsRefresh = { cameraName, preRoll, _, _, _, _, _, _, _, _ ->
                refreshed.add("$cameraName/$preRoll")
            },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        vm.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        assertEquals(listOf("Porch/9"), refreshed)
    }

    @Test
    fun startWithMatchingParamsDoesNotRefresh() {
        val refreshed = mutableListOf<String>()
        val vm = MonitorViewModel(
            application = ApplicationProvider.getApplicationContext(),
            permissionsGranted = { true },
            startMonitoring = { _, _, _, _, _, _, _, _ -> },
            stopMonitoring = {},
            settingsLoader = { AppSettings.defaults() },
            recordingParamsRefresh = { cameraName, preRoll, _, _, _, _, _, _, _, _ ->
                refreshed.add("$cameraName/$preRoll")
            },
            scheduleCheckInterval = null,
            surfaceRuntimeStartFailures = false,
            serviceHealth = { true },
        )
        vm.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MonitorState.Monitoring, vm.state.value)
        assertTrue(refreshed.isEmpty())
    }
}
