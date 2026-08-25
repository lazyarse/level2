package io.securitycam.level2.detection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.audio.YamnetClassifier
import io.securitycam.level2.detection.face.MediaPipeFaceEngine
import io.securitycam.level2.detection.person.YoloPersonEngine
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 on-device gate: the three real engines load their bundled models
 * and run end-to-end on synthetic frames (pixel_34_aosp).
 */
@RunWith(AndroidJUnit4::class)
class Phase3EnginesSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun yamnetClassifiesAZeroWindow() = runBlocking {
        val classifier = YamnetClassifier.load(context)
        assertNotNull("yamnet.tflite failed to load", classifier)
        classifier!!.init()
        val scores: AudioEventScores = classifier.classify(
            AudioWindow(timestamp = Instant.EPOCH, samples = FloatArray(15600), sampleRate = 16000),
        )
        assertEquals(setOf("baby_cry", "glass", "loud_noise", "dog_bark", "growl", "cat"), scores.classScores.keys)
        assertTrue(scores.classScores.values.all { it in 0.0..1.0 })
        classifier.dispose()
    }

    @Test
    fun yoloDetectsOnASyntheticFrame() = runBlocking {
        val engine = YoloPersonEngine(context)
        engine.init()
        // 320x240 mid-gray frame.
        val w = 320
        val h = 240
        val bgr = ByteArray(w * h * 3) { 120.toByte() }
        val boxes = engine.detectPersons(ColorBitmap(w, h, bgr))
        assertNotNull(boxes)
        assertTrue("expected few detections on a blank frame", boxes.size <= 30)
        engine.dispose()
    }

    @Test
    fun faceEngineDetectsOnABlankFrame() = runBlocking {
        val engine = MediaPipeFaceEngine(context)
        engine.init()
        val w = 64
        val h = 64
        val bgr = ByteArray(w * h * 3) { 100.toByte() }
        val faces = engine.detectFaces(ColorBitmap(w, h, bgr))
        assertNotNull(faces)
        assertTrue("no faces expected on a blank frame", faces.isEmpty())
        engine.dispose()
    }
}