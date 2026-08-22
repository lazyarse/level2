package io.securitycam.level1.detection.face

import io.securitycam.level1.detection.ColorBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure preprocessing/matching math of [FaceEmbeddingEngine] (no runtime). */
class FaceEmbeddingEngineTest {

    private fun solid(w: Int, h: Int, b: Int, g: Int, r: Int): ColorBitmap {
        val data = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            data[i * 3] = b.toByte()
            data[i * 3 + 1] = g.toByte()
            data[i * 3 + 2] = r.toByte()
        }
        return ColorBitmap(w, h, data)
    }

    private fun norm(v: Int): Float = ((v - 127.5f) / 127.5f)

    @Test
    fun buildInputHasModelDimensions() {
        val input = FaceEmbeddingEngine.buildInput(solid(64, 64, 0, 0, 0), doubleArrayOf(0.0, 0.0, 1.0, 1.0))
        assertEquals(1, input.size)
        assertEquals(FaceEmbeddingEngine.INPUT_SIZE * FaceEmbeddingEngine.INPUT_SIZE * 3, input[0].size)
    }

    @Test
    fun solidFrameMapsToNormalizedRgb() {
        // BGR (10, 20, 30): RGB layout expects R=30, G=20, B=10.
        val input = FaceEmbeddingEngine.buildInput(solid(8, 8, 10, 20, 30), doubleArrayOf(0.25, 0.25, 0.75, 0.75))
        val px = input[0]
        for (i in 0 until FaceEmbeddingEngine.INPUT_SIZE * FaceEmbeddingEngine.INPUT_SIZE) {
            assertEquals(norm(30), px[i * 3], 1e-6f)
            assertEquals(norm(20), px[i * 3 + 1], 1e-6f)
            assertEquals(norm(10), px[i * 3 + 2], 1e-6f)
        }
    }

    @Test
    fun fullFrameCropOn112PxSourceIsIdentity() {
        // Gradient BGR frame where value encodes position; box covers the whole
        // frame so each output pixel samples exactly its source pixel.
        val w = FaceEmbeddingEngine.INPUT_SIZE
        val data = ByteArray(w * w * 3) { ((it / 3) % 256).toByte() }
        val frame = ColorBitmap(w, w, data)
        val input = FaceEmbeddingEngine.buildInput(frame, doubleArrayOf(0.0, 0.0, 1.0, 1.0))[0]
        for (p in 0 until w * w) {
            // All three BGR channels of pixel p hold p % 256.
            assertEquals(norm(p % 256), input[p * 3], 1e-6f)
            assertEquals(norm(p % 256), input[p * 3 + 2], 1e-6f)
        }
    }

    @Test
    fun outOfBoundsBoxIsClampedWithoutThrowing() {
        val input = FaceEmbeddingEngine.buildInput(solid(16, 16, 5, 5, 5), doubleArrayOf(-0.5, -0.9, 0.4, 1.7))
        assertEquals(FaceEmbeddingEngine.INPUT_SIZE * FaceEmbeddingEngine.INPUT_SIZE * 3, input[0].size)
    }

    @Test
    fun cosineDistanceIdentities() {
        val x = floatArrayOf(1f, 0f, 2f)
        assertEquals(0.0, FaceEmbeddingEngine.cosineDistance(x, x.copyOf()), 1e-12)
        val ortho = floatArrayOf(0f, 1f, 0f)
        assertEquals(1.0, FaceEmbeddingEngine.cosineDistance(x, ortho), 1e-9)
        val opposite = floatArrayOf(-1f, 0f, -2f)
        assertEquals(2.0, FaceEmbeddingEngine.cosineDistance(x, opposite), 1e-9)
        assertEquals(1.0, FaceEmbeddingEngine.cosineDistance(FloatArray(3), x), 1e-9)
    }

    @Test
    fun l2NormalizeScalesToUnitLengthAndKeepsZero() {
        val v = FaceEmbeddingEngine.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
        assertTrue(FaceEmbeddingEngine.l2Normalize(FloatArray(4)).contentEquals(FloatArray(4)))
    }
}
