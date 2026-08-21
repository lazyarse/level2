package io.securitycam.level1

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.person.YoloPersonEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native port of `integration_test/person_detection_linux_test.dart`, using the
 * REAL YOLO26n engine:
 *  - bundled images that contain people each yield at least one person box,
 *  - a blank frame yields (near-)zero detections on the native stack, matching
 *    the Phase 3 smoke gate's leniency for model noise.
 */
@RunWith(AndroidJUnit4::class)
class PersonDetectionTest {

    private val personAssets = listOf("messi5.jpg", "astronaut.png", "camera.png")

    @Test
    fun personEngineLoadsAndReportsFewBoxesOnABlankFrame() = runBlocking {
        val engine = YoloPersonEngine(ItestHarness.appContext)
        engine.init()
        val w = 320
        val h = 240
        val frame = ColorBitmap(w, h, ByteArray(w * h * 3) { 120.toByte() })
        val people = engine.detectPersons(frame)
        assertTrue(
            "expected near-zero detections on a blank frame, got ${people.size}",
            people.size <= 5,
        )
        engine.dispose()
    }

    private fun detectsAPersonIn(asset: String) = runBlocking {
        val engine = YoloPersonEngine(ItestHarness.appContext)
        engine.init()
        // Cap the long edge so the emulator CPU inference stays quick.
        val frame = ItestHarness.loadBgrScaled(asset, maxDim = 1024)
        val people = engine.detectPersons(frame)
        assertTrue("no person detected in $asset", people.isNotEmpty())
        val box = people.first()
        assertTrue(box.x1 >= 0.0)
        assertTrue(box.y1 >= 0.0)
        assertTrue(box.x2 <= frame.width.toDouble())
        assertTrue(box.y2 <= frame.height.toDouble())
        assertTrue(box.x2 - box.x1 > 0.0)
        assertTrue(box.y2 - box.y1 > 0.0)
        engine.dispose()
    }

    @Test
    fun detectsAPersonInMessi() = detectsAPersonIn("messi5.jpg")

    @Test
    fun detectsAPersonInAstronaut() = detectsAPersonIn("astronaut.png")

    @Test
    fun detectsAPersonInCamera() = detectsAPersonIn("camera.png")
}
