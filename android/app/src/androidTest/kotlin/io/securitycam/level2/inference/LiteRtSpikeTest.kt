package io.securitycam.level2.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LiteRT on-device smoke test (runs on pixel_34_aosp).
 * Verifies both Interpreter (YAMNet) and CompiledModel (YOLO) load and run.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtSpikeTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun yamnetInterpreterLoadsAndRunsZeroInput() {
        val liteRt = LiteRt.create(context)
        assertTrue("yamnet.tflite not found or failed to load", liteRt.yamnetAvailable)

        val zeroInput = FloatArray(15600)
        val out = liteRt.classifyYamnet(zeroInput)
        assertNotNull(out)
        assertEquals(521, out!!.size)
    }

    @Test
    fun yoloCompiledModelLoadsAndRunsZeroInput() {
        val liteRt = LiteRt.create(context)
        assertTrue("yolo26n_w8a32.tflite not found or failed to load", liteRt.yoloAvailable)

        val out = liteRt.detectYoloZero()
        assertNotNull(out)
        // Expected output shape: [1, 84, 8400] = 705,600 elements
        assertEquals(84 * 8400, out!!.size)
    }
}