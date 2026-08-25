package io.securitycam.level2

import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.MediaPipeFaceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Native port of `integration_test/face_detection_linux_test.dart`, using the
 * REAL MediaPipe BlazeFace engine:
 *  - a blank frame yields zero detections (sanity + load check),
 *  - bundled real-world images each yield at least one plausible face box.
 *
 * Assets are license-safe: OpenCV sample data (BSD-3-Clause) and scikit-image
 * sample data (public domain / BSD).
 */
@RunWith(AndroidJUnit4::class)
class FaceDetectionTest {

    private val faceAssets = listOf("messi5.jpg", "astronaut.png", "camera.png")

    @Test
    fun faceEngineLoadsAndRunsOnABlankFrame() = runBlocking {
        val engine = MediaPipeFaceEngine(ItestHarness.appContext)
        engine.init()
        val frame = ColorBitmap(128, 128, ByteArray(128 * 128 * 3) { 128.toByte() })
        val faces = engine.detectFaces(frame)
        assertTrue("no faces expected on a blank frame", faces.isEmpty())
        engine.dispose()
    }

    private fun assertPlausibleBox(
        box: io.securitycam.level2.detection.face.FaceDetection,
        frame: ColorBitmap,
    ) {
        assertTrue(box.x1 >= 0.0)
        assertTrue(box.y1 >= 0.0)
        assertTrue(box.x2 <= frame.width.toDouble())
        assertTrue(box.y2 <= frame.height.toDouble())
        assertTrue(box.x2 - box.x1 > 0.0)
        assertTrue(box.y2 - box.y1 > 0.0)
    }

    private fun detectsAFaceIn(asset: String) = runBlocking {
        val engine = MediaPipeFaceEngine(ItestHarness.appContext)
        engine.init()
        // The bundled SHORT-RANGE model needs the face near the camera; the
        // Flutter app used the full-range back-camera model and detected the
        // distant face in e.g. messi5.jpg directly. Walk inward with centered
        // crops (subject approaching) and require a hit before the closest.
        var hit: io.securitycam.level2.detection.face.FaceDetection? = null
        var frame: ColorBitmap? = null
        for (fraction in listOf(1.0, 0.75, 0.5, 0.35)) {
            val candidate = ItestHarness.loadBgrCropped(asset, fraction)
            val faces = engine.detectFaces(candidate)
            if (faces.isNotEmpty()) {
                hit = faces.first()
                frame = candidate
                println("[itest] face in $asset detected at crop=$fraction")
                break
            }
        }
        assertTrue("no face detected in $asset at any crop", hit != null)
        assertPlausibleBox(hit!!, frame!!)
        engine.dispose()
    }

    @Test
    fun detectsAFaceInMessi() = detectsAFaceIn("messi5.jpg")

    @Test
    fun detectsAFaceInAstronaut() = detectsAFaceIn("astronaut.png")

    @Test
    fun detectsAFaceInCamera() = detectsAFaceIn("camera.png")
}
