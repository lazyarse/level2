package io.securitycam.level1.detection.face

import android.content.Context
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.inference.TfliteAssets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory

/**
 * MobileFaceNet (`mobilefacenet.tflite`) embedding extractor: a face box on
 * the BGR color analysis frame becomes a square-padded 112x112 RGB crop,
 * normalized to [-1, 1]; the model outputs an L2-normalizable 192-d vector.
 * Preprocessing is pure ([buildInput]) so it unit-tests without the runtime;
 * model-load failures yield null embeddings (callers fall back to plain
 * face-detection behavior).
 */
class FaceEmbeddingEngine private constructor(
    private val interpreter: InterpreterApi,
) {

    /** Highest-confidence face only; one extra inference per frame at most. */
    fun embed(frame: ColorBitmap, box: DoubleArray): FloatArray? {
        if (box.size < 4) return null
        val input = buildInput(frame, box)
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interpreter.run(arrayOf(input), output)
        return output[0]
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_DIM = 192

        /** Loads the bundled MobileFaceNet; null when unavailable. */
        fun load(context: Context): FaceEmbeddingEngine? = try {
            val model = TfliteAssets.loadModelFile(context, MODEL_ASSET)
            FaceEmbeddingEngine(
                InterpreterFactory().create(
                    model,
                    InterpreterApi.Options().setNumThreads(1),
                ),
            )
        } catch (_: Exception) {
            null
        }

        /**
         * Builds the 1x112x112x3 float32 NHWC input: square-padded crop of
         * [frame] covering the normalized [box] (x1,y1,x2,y2 in 0..1), BGR→RGB,
         * each byte mapped to (v - 127.5) / 127.5.
         */
        fun buildInput(frame: ColorBitmap, box: DoubleArray): Array<FloatArray> {
            val w = frame.width
            val h = frame.height
            // Pixel rect, clamped to the frame.
            val px0 = (box[0] * w).roundToInt().coerceIn(0, w - 1)
            val py0 = (box[1] * h).roundToInt().coerceIn(0, h - 1)
            val px1 = (box[2] * w).roundToInt().coerceIn(px0 + 1, w)
            val py1 = (box[3] * h).roundToInt().coerceIn(py0 + 1, h)
            val pw = px1 - px0
            val ph = py1 - py0
            // Square window centered on the box, clamped to stay in-frame.
            val side = max(pw, ph)
            var cx = px0 + pw / 2.0
            var cy = py0 + ph / 2.0
            cx = cx.coerceIn(side / 2.0, w - side / 2.0)
            cy = cy.coerceIn(side / 2.0, h - side / 2.0)
            val sx0 = cx - side / 2.0
            val sy0 = cy - side / 2.0

            val image = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
            for (oy in 0 until INPUT_SIZE) {
                val sy = sy0 + (oy + 0.5) * side / INPUT_SIZE - 0.5
                val y0 = sy.toInt().coerceIn(0, h - 1)
                val y1 = min(y0 + 1, h - 1)
                val fy = (sy - y0).coerceIn(0.0, 1.0)
                for (ox in 0 until INPUT_SIZE) {
                    val sx = sx0 + (ox + 0.5) * side / INPUT_SIZE - 0.5
                    val x0 = sx.toInt().coerceIn(0, w - 1)
                    val x1 = min(x0 + 1, w - 1)
                    val fx = (sx - x0).coerceIn(0.0, 1.0)
                    val dst = (oy * INPUT_SIZE + ox) * 3
                    for (c in 0 until 3) {
                        // Bilinear over the BGR channel c, then swap to RGB.
                        val v00 = frame.bgr[(y0 * w + x0) * 3 + c].toInt() and 0xFF
                        val v10 = frame.bgr[(y0 * w + x1) * 3 + c].toInt() and 0xFF
                        val v01 = frame.bgr[(y1 * w + x0) * 3 + c].toInt() and 0xFF
                        val v11 = frame.bgr[(y1 * w + x1) * 3 + c].toInt() and 0xFF
                        val top = v00 + (v10 - v00) * fx
                        val bottom = v01 + (v11 - v01) * fx
                        val v = top + (bottom - top) * fy
                        image[dst + rgbIndex(c)] = ((v - 127.5f) / 127.5f).toFloat()
                    }
                }
            }
            return arrayOf(image)
        }

        /** Destination slot for BGR channel [c] in an RGB layout. */
        private fun rgbIndex(bgrChannel: Int): Int = when (bgrChannel) {
            0 -> 2 // B -> R position
            2 -> 0 // R -> B position
            else -> 1
        }

        /** Cosine distance in [0, 2]: 1 - cos(a, b); zero-norm safe. */
        fun cosineDistance(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            if (na == 0.0 || nb == 0.0) return 1.0
            val cos = dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
            return 1.0 - cos.coerceIn(-1.0, 1.0)
        }

        /** L2-normalizes [v] in place-safe fashion (returns a copy). */
        fun l2Normalize(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            norm = kotlin.math.sqrt(norm)
            if (norm == 0.0) return v.copyOf()
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}
