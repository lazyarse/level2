package io.securitycam.level2.ui.settings

import io.securitycam.level2.BuildConfig
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fdroid flavor ships no embedding weights, so enrollment entry points
 * refuse up-front with a message instead of starting a doomed capture.
 * Runs only on flavors without face recognition (the full flavor covers
 * the happy paths in [FaceEnrollmentViewModelTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceEnrollmentUnsupportedTest {

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        settingsLoader = { AppSettings.defaults() },
        settingsSaver = {},
        eventsClearer = {},
    )

    @Test
    fun startEnrollmentRefusedWhenUnsupported() {
        Assume.assumeFalse(
            "full flavor supports enrollment",
            BuildConfig.FACE_RECOGNITION_SUPPORTED,
        )
        val vm = viewModel()

        vm.startEnrollment("Bob")

        assertNull(vm.enrollingLabel.value)
        assertEquals(
            "Face recognition isn't available in this build",
            vm.message.value,
        )
    }

    @Test
    fun startSampleCaptureRefusedWhenUnsupported() {
        Assume.assumeFalse(
            "full flavor supports enrollment",
            BuildConfig.FACE_RECOGNITION_SUPPORTED,
        )
        val vm = viewModel()

        vm.startSampleCapture(KnownFace(id = "face_x", label = "X"))

        assertNull(vm.enrollingLabel.value)
        assertEquals(
            "Face recognition isn't available in this build",
            vm.message.value,
        )
    }
}
