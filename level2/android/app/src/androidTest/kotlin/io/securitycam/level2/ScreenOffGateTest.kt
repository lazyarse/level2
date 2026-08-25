package io.securitycam.level2

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.monitor.MonitorState
import io.securitycam.level2.monitor.MonitorViewModel
import io.securitycam.level2.storage.AppDatabase
import io.securitycam.level2.storage.EncryptedSecretStore
import io.securitycam.level2.storage.RecordedEventRow
import io.securitycam.level2.storage.RoomEventLog
import io.securitycam.level2.storage.SettingsStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen-off continuity gate (native port of
 * `integration_test/screen_off_gate_test.dart`): while the FGS runs with the
 * camera+microphone type, analysis and the event pipeline must keep working
 * with the display off.
 *
 * The host runner watches logcat for `[itest] SCREEN_OFF_READY`, toggles the
 * screen off ~5 s later, and turns it back on when `SCREEN_OFF_DONE` appears.
 *
 * Runs with `recordVideo=false`: the software AVC encoder starves the
 * emulator's camera under swiftshader over long windows. Clip recording is
 * covered by [MonitoringInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffGateTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var vm: MonitorViewModel

    @Before
    fun setUp() = runBlocking {
        clearEvents()
        saveSettings { it.copy(recordVideo = false) }
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::vm.isInitialized && vm.state.value != MonitorState.Idle) {
                vm.stop()
            }
            saveSettings { AppSettings.defaults() }
        }
    }

    private suspend fun saveSettings(transform: (AppSettings) -> AppSettings) {
        val store = SettingsStore(context, EncryptedSecretStore(context))
        store.save(transform(store.load()))
    }

    private suspend fun clearEvents() {
        RoomEventLog(AppDatabase.get(context).eventDao())
            .deleteEvents(Instant.now().plusSeconds(3600))
    }

    private suspend fun recent(): List<RecordedEventRow> =
        RoomEventLog(AppDatabase.get(context).eventDao()).recent(200)

    private fun awaitState(target: MonitorState, timeoutMs: Long = 120_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (vm.state.value != target && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
        }
        assertEquals("controller.error=${vm.error.value}", target, vm.state.value)
    }

    @Test
    fun monitoringSurvivesAScreenOffWindow() = runBlocking {
        vm = MonitorViewModel(application = context as android.app.Application)
        vm.start()
        awaitState(MonitorState.Monitoring)

        // Baseline: first motion event.
        val baselineDeadline = SystemClock.elapsedRealtime() + 3 * 60_000L
        var baseline = 0
        while (SystemClock.elapsedRealtime() < baselineDeadline) {
            baseline = recent().size
            if (baseline > 0) break
            Thread.sleep(2_000)
        }
        assertTrue("no baseline motion event", baseline > 0)
        ItestHarness.mark("SCREEN_OFF_READY")

        // Window during which the host keeps the display off (~45 s). Assert
        // monitoring stays healthy throughout; a second event may already land.
        var secondEvent = false
        var windowError = false
        val windowEnd = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < windowEnd) {
            Thread.sleep(2_000)
            if (vm.error.value != null || vm.state.value != MonitorState.Monitoring) {
                windowError = true
                break
            }
            if (recent().size > baseline) {
                secondEvent = true
                break
            }
        }
        ItestHarness.mark("SCREEN_OFF_DONE")

        // Display is back on: a second motion event must arrive within the
        // recovery window (camera survived + resumed full-rate analysis).
        val recoveryEnd = SystemClock.elapsedRealtime() + 90_000
        while (!secondEvent && SystemClock.elapsedRealtime() < recoveryEnd) {
            Thread.sleep(2_000)
            if (recent().size > baseline) secondEvent = true
        }

        assertTrue(
            "monitoring stopped/errored during screen-off: ${vm.error.value}",
            !windowError,
        )
        assertEquals(
            "monitoring stopped/errored during screen-off: ${vm.error.value}",
            MonitorState.Monitoring,
            vm.state.value,
        )
        assertNull(vm.error.value)
        assertTrue(
            "no motion event recorded after the screen-off window (camera likely stalled)",
            secondEvent,
        )

        vm.stop()
        awaitState(MonitorState.Idle)
    }
}
