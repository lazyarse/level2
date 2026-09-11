package io.securitycam.level2.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the generated test-send snapshot (needs real
 * android.graphics.Bitmap, unavailable on the plain JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleSnapshotTest {

    @Test
    fun generatorProducesNamedJpegBytes() {
        val snap = sampleTestSnapshot()

        assertEquals("test-snapshot.jpg", snap.name)
        assertEquals("image/jpeg", snap.mimeType)
        assertTrue(snap.bytes.isNotEmpty())
    }

    @Test
    fun smallSnapshotPassesThroughUntouched() {
        val snap = io.securitycam.level2.core.Snapshot(
            byteArrayOf(1, 2, 3),
            "image/png",
            "snap.png",
        )

        assertEquals(snap, fitSnapshotForUpload(snap, maxBytes = 1024))
    }

    @Test
    fun oversizeSnapshotDownscalesUnderCap() {
        val width = 1600
        val height = 1200
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            android.graphics.Color.rgb(x * 255 / width, y * 255 / height, 128)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
        bitmap.recycle()
        val source = out.toByteArray()
        assertTrue("test setup: source must exceed the cap, was ${source.size}", source.size > 90_000)
        val snap = io.securitycam.level2.core.Snapshot(source, "image/jpeg", "big.png")

        val fitted = fitSnapshotForUpload(snap, maxBytes = source.size / 3)

        assertTrue(fitted != null)
        assertTrue(fitted!!.bytes.size <= source.size / 3)
        assertEquals("image/jpeg", fitted.mimeType)
        assertEquals("big.jpg", fitted.name)
        assertTrue(
            android.graphics.BitmapFactory.decodeByteArray(
                fitted.bytes, 0, fitted.bytes.size,
            ) != null,
        )
    }

    @Test
    fun corruptBytesFallBackToNull() {
        val snap = io.securitycam.level2.core.Snapshot(
            "not-an-image-at-all".toByteArray(),
            "image/jpeg",
            "x.jpg",
        )

        assertEquals(null, fitSnapshotForUpload(snap, maxBytes = 10))
    }
}
