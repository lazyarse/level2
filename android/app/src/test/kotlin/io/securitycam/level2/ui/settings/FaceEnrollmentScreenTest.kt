package io.securitycam.level2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.ui.theme.SecurityCamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Face enrollment capture page phases: LIVE (oval guide + shutter) and
 * REVIEW (captured frame + Use/Retake), plus the inline no-face error.
 * One flow per test (dialog-free, but keeps recomposition state isolated).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceEnrollmentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun frame() = ColorBitmap(8, 8, ByteArray(3 * 8 * 8) { 0x40 })
    private fun det() = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)

    @Test
    fun livePhase_showsOvalAndArmedShutter_noReviewControls() {
        compose.setContent {
            SecurityCamTheme {
                FaceEnrollmentScreen(
                    label = "Bob",
                    onCancel = {},
                    shutterArmed = true,
                )
            }
        }
        compose.onNodeWithTag("enrollmentPreview").assertIsDisplayed()
        compose.onNodeWithTag("enrollmentOvalGuide").assertIsDisplayed()
        compose.onNodeWithTag("enrollmentShutterButton")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithTag("usePhotoButton").assertDoesNotExist()
        compose.onNodeWithTag("retakePhotoButton").assertDoesNotExist()
        compose.onNodeWithText("Position your face in the oval.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun livePhase_disablesShutterUntilArmed() {
        compose.setContent {
            SecurityCamTheme {
                FaceEnrollmentScreen(
                    label = "Bob",
                    onCancel = {},
                    shutterArmed = false,
                )
            }
        }
        compose.onNodeWithTag("enrollmentShutterButton")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun livePhase_showsInlineNoFaceErrorInsteadOfHint() {
        compose.setContent {
            SecurityCamTheme {
                FaceEnrollmentScreen(
                    label = "Bob",
                    onCancel = {},
                    shutterArmed = true,
                    error = "No face detected — try again",
                )
            }
        }
        compose.onNodeWithTag("enrollmentError").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No face detected — try again")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Position your face in the oval.")
            .assertDoesNotExist()
        compose.onNodeWithTag("enrollmentShutterButton")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun reviewPhase_showsCapturedPhotoWithUseAndRetake() {
        compose.setContent {
            SecurityCamTheme {
                FaceEnrollmentScreen(
                    label = "Bob",
                    onCancel = {},
                    capturedFrame = frame() to det(),
                    shutterArmed = true,
                )
            }
        }
        compose.onNodeWithTag("enrollmentReview").assertIsDisplayed()
        compose.onNodeWithText("Check the photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("usePhotoButton").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retakePhotoButton").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("enrollmentShutterButton").assertDoesNotExist()
        compose.onNodeWithTag("enrollmentOvalGuide").assertDoesNotExist()
        compose.onNodeWithTag("enrollmentPreview").assertDoesNotExist()
        compose.onNodeWithTag("cancelEnrollmentButton").assertIsDisplayed()
    }
}
