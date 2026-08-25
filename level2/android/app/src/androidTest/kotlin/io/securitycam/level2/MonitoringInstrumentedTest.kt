package io.securitycam.level2

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.securitycam.level2.camera_service.VideoClipRecorder
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.storage.AppDatabase
import io.securitycam.level2.monitor.MonitorState
import io.securitycam.level2.monitor.MonitorViewModel
import io.securitycam.level2.storage.EncryptedSecretStore
import io.securitycam.level2.storage.FileSnapshotStore
import io.securitycam.level2.storage.RoomEventLog
import io.securitycam.level2.storage.SettingsStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native port of `integration_test/monitoring_on_device_test.dart`: drives the
 * real camera service (CameraX + FGS + mic) on an emulator and asserts the
 * motion → snapshot → clip pipeline end to end.
 *
 * The host runner grants CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS via `pm grant`
 * before this class runs; `[itest]` markers go to logcat (`Log.i("itest",…)`).
 */
@RunWith(AndroidJUnit4::class)
class MonitoringInstrumentedTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var vm: MonitorViewModel

    /** How long to wait for the first motion trigger (slow swiftshader boot). */
    private val pollTimeoutMs = 3 * 60_000L
    private val pollIntervalMs = 2_000L

    @Before
    fun setUp() = runBlocking {
        // Fresh event log per test so stale rows can't satisfy predicates.
        clearEvents()
        saveSettings { AppSettings.defaults() }
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::vm.isInitialized && vm.state.value != MonitorState.Idle) {
                vm.stop()
                awaitState(MonitorState.Idle, 60_000)
            }
            saveSettings { AppSettings.defaults() }
        }
    }

    private fun settingsStore(): SettingsStore =
        SettingsStore(context, EncryptedSecretStore(context))

    private suspend fun saveSettings(transform: (AppSettings) -> AppSettings) {
        val store = settingsStore()
        store.save(transform(store.load()))
    }

    private suspend fun clearEvents() {
        RoomEventLog(AppDatabase.get(context).eventDao())
            .deleteEvents(Instant.now().plusSeconds(3600))
    }

    private suspend fun recentEvents() = RoomEventLog(AppDatabase.get(context).eventDao()).recent(200)

    private fun newVm(permissionsGranted: () -> Boolean = { true }): MonitorViewModel =
        MonitorViewModel(
            application = context as android.app.Application,
            permissionsGranted = permissionsGranted,
        )

    private fun awaitState(target: MonitorState, timeoutMs: Long = 120_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (vm.state.value != target && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
        }
        assertEquals("controller.error=${vm.error.value}", target, vm.state.value)
    }

    private suspend fun waitForEvent(
        predicate: (io.securitycam.level2.storage.RecordedEventRow) -> Boolean,
        timeoutMs: Long = pollTimeoutMs,
    ): io.securitycam.level2.storage.RecordedEventRow? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            for (row in recentEvents()) {
                if (predicate(row)) return row
            }
            Thread.sleep(pollIntervalMs)
        }
        return null
    }

    @Test
    fun permissionGateBlocksStartWhenDenied() {
        vm = newVm(permissionsGranted = { false })
        vm.start()
        awaitState(MonitorState.Error, 10_000)
        assertTrue(
            "expected a permissions error, got: ${vm.error.value}",
            vm.error.value?.contains("permissions") == true,
        )
    }

    @Test
    fun fullMonitoringRunRecordsMotionSnapshotAndClip() = runBlocking {
        vm = newVm()
        vm.start()
        awaitState(MonitorState.Monitoring)
        ItestHarness.mark("MONITORING_STARTED")

        // The emulator virtual scene moves continuously; overlapping batches can
        // drop one export (one clip at a time), so require the motion row that
        // actually carries a clip reference.
        val motion = waitForEvent(
            { row ->
                (row.triggerType == TriggerType.motion || row.triggerTypes.contains(TriggerType.motion)) &&
                    row.videoName != null
            },
        )
        assertNotNull("no motion event on the device", motion)
        val event = motion!!
        assertNotNull("event has no snapshot reference", event.snapshotName)

        val snapDir = File(context.filesDir, "snapshots")
        val files = snapDir.listFiles { f ->
            f.name.endsWith(".png") || f.name.endsWith(".jpg")
        }.orEmpty()
        assertTrue("no snapshot PNG written", files.isNotEmpty())

        val videoName = event.videoName!!
        assertTrue("clip name not .mp4: $videoName", videoName.endsWith(".mp4"))
        assertTrue("clip not found in MediaStore: $videoName", VideoClipRecorder.exists(videoName))
        val info = VideoClipRecorder.videoInfo(videoName)
        assertNotNull("videoInfo returned null for $videoName", info)
        assertTrue((info!!["width"] ?: 0) > 0)
        assertTrue((info["height"] ?: 0) > 0)
        assertTrue("clip is not landscape", info["width"]!! >= info["height"]!!)
        val expectAudio =
            InstrumentationRegistry.getArguments().getString("expectClipAudio") == "true"
        assertEquals(
            "clip audio track mismatch for $videoName",
            expectAudio,
            VideoClipRecorder.hasAudio(videoName),
        )
        VideoClipRecorder.delete(videoName)
        assertTrue("deleteVideo did not remove the clip", !VideoClipRecorder.exists(videoName))
        ItestHarness.mark("EVENT_RECORDED")

        vm.stop()
        awaitState(MonitorState.Idle)
    }

    @Test
    fun faceDetectorIsWiredAndMotionGated() = runBlocking {
        saveSettings { settings ->
            settings.copy(
                detectorConfigs = settings.detectorConfigs.mapValues { (key, config) ->
                    if (key == TriggerType.face) {
                        config.copy(enabled = true, motionGated = true)
                    } else {
                        config
                    }
                },
            )
        }
        vm = newVm()
        vm.start()
        awaitState(MonitorState.Monitoring)
        ItestHarness.mark("FACE_MONITORING_STARTED")

        // No real face in the emulator scene, so no face trigger is expected:
        // gate is that the async motion-gated path survives 30 s without error.
        val windowEnd = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < windowEnd &&
            vm.state.value == MonitorState.Monitoring
        ) {
            Thread.sleep(2_000)
        }
        assertEquals(
            "face-enabled monitoring crashed: ${vm.error.value}",
            MonitorState.Monitoring,
            vm.state.value,
        )

        vm.stop()
        awaitState(MonitorState.Idle)
    }

    @Test
    fun personDetectorIsWiredAndMotionGated() = runBlocking {
        saveSettings { settings ->
            settings.copy(
                detectorConfigs = settings.detectorConfigs.mapValues { (key, config) ->
                    if (key == TriggerType.person) {
                        config.copy(enabled = true, motionGated = true)
                    } else {
                        config
                    }
                },
            )
        }
        vm = newVm()
        vm.start()
        awaitState(MonitorState.Monitoring)
        ItestHarness.mark("PERSON_MONITORING_STARTED")

        val windowEnd = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < windowEnd &&
            vm.state.value == MonitorState.Monitoring
        ) {
            Thread.sleep(2_000)
        }
        assertEquals(
            "person-enabled monitoring crashed: ${vm.error.value}",
            MonitorState.Monitoring,
            vm.state.value,
        )

        vm.stop()
        awaitState(MonitorState.Idle)
    }
}
