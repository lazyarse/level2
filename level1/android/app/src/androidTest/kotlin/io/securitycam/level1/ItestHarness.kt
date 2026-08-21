package io.securitycam.level1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.securitycam.level1.detection.ColorBitmap

/**
 * Shared helpers for the on-device integration suite (port of the Dart
 * `DeviceHarness` in `integration_test/monitoring_on_device_test.dart`).
 *
 * The host runner (`tool/run_android_integration_tests.sh`) pre-grants
 * CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS via `pm grant`, so the real system
 * permission state is granted and no dialog can block a test. `[itest]`
 * markers are emitted through `Log.i("itest", …)` for the host to coordinate
 * the screen-off / wake sequence from logcat.
 */
object ItestHarness {

    /** Emits a marker the host runner parses from logcat. */
    fun mark(name: String) {
        Log.i("itest", name)
    }

    val instrumentationContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    /**
     * The app-under-test context. Engines (MediaPipe/ML Kit internals require
     * a real application context) and app storage must use this one; only
     * test-APK assets go through [instrumentationContext].
     */
    val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Decodes an androidTest asset into a BGR [ColorBitmap] (port of the
     * Dart `loadBgr` helper used by the face/person engine gates).
     */
    fun loadBgr(assetName: String): ColorBitmap {
        instrumentationContext.assets.open(assetName).use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            check(bitmap != null) { "could not decode $assetName" }
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val bgr = ByteArray(w * h * 3)
            var i = 0
            for (p in pixels) {
                bgr[i++] = (p and 0xFF).toByte()          // B
                bgr[i++] = ((p shr 8) and 0xFF).toByte()  // G
                bgr[i++] = ((p shr 16) and 0xFF).toByte() // R
            }
            bitmap.recycle()
            return ColorBitmap(w, h, bgr)
        }
    }

    /**
     * Decodes an asset and returns a CENTERED CROP covering [fraction] of
     * each axis (simulating a subject standing closer to the camera).
     */
    fun loadBgrCropped(assetName: String, fraction: Double): ColorBitmap {
        val full = loadBgr(assetName)
        if (fraction >= 1.0) return full
        val w = (full.width * fraction).toInt()
        val h = (full.height * fraction).toInt()
        val x0 = (full.width - w) / 2
        val y0 = (full.height - h) / 2
        val bgr = ByteArray(w * h * 3)
        for (y in 0 until h) {
            System.arraycopy(
                full.bgr, ((y0 + y) * full.width + x0) * 3,
                bgr, y * w * 3, w * 3,
            )
        }
        return ColorBitmap(w, h, bgr)
    }

    /** Downscales an asset so YOLO's 640-class inputs stay fast on emulators. */
    fun loadBgrScaled(assetName: String, maxDim: Int): ColorBitmap {
        // First pass: bounds only, to pick a power-of-two sample size.
        val sample = instrumentationContext.assets.open(assetName).use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            var s = 1
            while (bounds.outWidth / s > maxDim || bounds.outHeight / s > maxDim) {
                s *= 2
            }
            s
        }
        instrumentationContext.assets.open(assetName).use { stream ->
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap: Bitmap = BitmapFactory.decodeStream(stream, null, options)!!
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val bgr = ByteArray(w * h * 3)
            var i = 0
            for (p in pixels) {
                bgr[i++] = (p and 0xFF).toByte()
                bgr[i++] = ((p shr 8) and 0xFF).toByte()
                bgr[i++] = ((p shr 16) and 0xFF).toByte()
            }
            bitmap.recycle()
            return ColorBitmap(w, h, bgr)
        }
    }
}
