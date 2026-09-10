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
}
