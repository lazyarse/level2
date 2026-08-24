package io.securitycam.level1.ui.settings

import android.os.Looper
import io.securitycam.level1.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [SettingsViewModel.beginRegionPreview]/[endRegionPreview]:
 * the region editor's live preview starts a preview-only session only when no
 * session is active, and close never stops a session it doesn't own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionPreviewViewModelTest {

    private class Session {
        var active: Boolean = false
        var startedIds: MutableList<String> = mutableListOf()
        var startCount: Int = 0
        var stopCount: Int = 0
    }

    private fun viewModel(session: Session): SettingsViewModel = SettingsViewModel(
        settingsLoader = { AppSettings.defaults() },
        settingsSaver = {},
        eventsClearer = {},
        cameraActive = { session.active },
        startCameraSession = { cameraId ->
            session.startCount++
            session.startedIds.add(cameraId)
            // Model the controller: the session becomes active once bound.
            session.active = true
        },
        stopCameraSession = {
            session.stopCount++
            session.active = false
        },
        framesWaitTimeoutMs = 200,
        framesSettleMs = 10,
    )

    private fun pump() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    @Test
    fun beginStartsPreviewSessionWithDraftCamera() {
        val session = Session()
        val vm = viewModel(session)

        vm.beginRegionPreview()
        pump()

        assertEquals(1, session.startCount)
        assertEquals(listOf("0"), session.startedIds)
        assertTrue(session.active)
    }

    @Test
    fun beginIsNoOpWhenCameraAlreadyActive() {
        val session = Session()
        session.active = true
        val vm = viewModel(session)

        vm.beginRegionPreview()
        pump()

        assertEquals(0, session.startCount)
    }

    @Test
    fun endStopsOnlyTheSessionWeStarted() {
        val session = Session()
        val vm = viewModel(session)

        vm.endRegionPreview()
        assertEquals(0, session.stopCount)

        vm.beginRegionPreview()
        pump()
        vm.endRegionPreview()
        pump()

        assertEquals(1, session.startCount)
        assertEquals(1, session.stopCount)
        assertFalse(session.active)
    }

    @Test
    fun endDoesNotStopAForeignActiveSession() {
        val session = Session()
        session.active = true
        val vm = viewModel(session)

        vm.beginRegionPreview()
        vm.endRegionPreview()
        pump()

        assertEquals(0, session.stopCount)
        assertTrue(session.active)
    }
}
