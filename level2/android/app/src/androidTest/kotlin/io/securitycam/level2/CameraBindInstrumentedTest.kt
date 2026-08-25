package io.securitycam.level2

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.camera_service.CameraRotations
import io.securitycam.level2.camera_service.VideoClipRecorder
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the 2026-08-23 black-preview incident: divergent target
 * rotations inside one UseCaseGroup made `bindToLifecycle()` fail session
 * configuration on both attempts, leaving monitoring running with no camera
 * opened (mic-only FGS indicator).
 *
 * Binds the exact composition monitoring ships — analysis + capture + preview
 * + video, all sharing one rotation from [CameraRotations] — against the real
 * HAL inside a resumed activity and asserts the camera opens and unbinds
 * cleanly.
 */
@RunWith(AndroidJUnit4::class)
class CameraBindInstrumentedTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun fullUseCaseGroupBindsWithSharedRotation() {
        var bindError: Throwable? = null
        var cameraHandle: Any? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    val provider = ProcessCameraProvider
                        .getInstance(context)
                        .get(5, TimeUnit.SECONDS)
                    val r = CameraRotations.resolve(activity.display?.rotation ?: 0)

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(r.analysis)
                        .build()
                    val capture = ImageCapture.Builder()
                        .setTargetRotation(r.capture)
                        .build()
                    val preview = Preview.Builder()
                        .setTargetRotation(r.preview)
                        .build()
                    val video = VideoClipRecorder.buildVideoCapture(r.video)

                    val group = UseCaseGroup.Builder()
                        .addUseCase(analysis)
                        .addUseCase(capture)
                        .addUseCase(preview)
                        .addUseCase(video)
                        .build()

                    val camera = provider.bindToLifecycle(
                        activity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                    cameraHandle = camera
                    provider.unbindAll()
                } catch (t: Throwable) {
                    bindError = t
                }
            }
        }

        assertNull(bindError)
        assertNotNull("camera handle missing", cameraHandle)
    }

    private fun assertNull(t: Throwable?) {
        if (t != null) throw AssertionError("bind failed", t)
    }
}
