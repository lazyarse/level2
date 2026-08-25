package io.securitycam.level2.camera_service

import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.Level2App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Playback helpers must work from process start (Level2App attaches the app
 * context in onCreate), not only after the first monitoring session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoClipRecorderContextTest {

    @Test
    fun openWithoutConfigureReportsMissingClipNotMissingContext() {
        val app = ApplicationProvider.getApplicationContext<Level2App>()
        // Level2App.onCreate already attached; be explicit for determinism.
        VideoClipRecorder.attach(app)

        val error = VideoClipRecorder.open("nonexistent_clip.mp4")
        assertEquals("no such clip: nonexistent_clip.mp4", error)
    }

    @Test
    fun existsIsFalseForUnknownClipAfterAttach() {
        VideoClipRecorder.attach(ApplicationProvider.getApplicationContext())
        assertTrue(!VideoClipRecorder.exists("nonexistent_clip.mp4"))
    }
}
