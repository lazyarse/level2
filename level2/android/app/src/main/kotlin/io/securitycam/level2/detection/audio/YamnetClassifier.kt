package io.securitycam.level2.detection.audio

import android.content.Context
import io.securitycam.level2.detection.AudioWindow
import io.securitycam.level2.inference.TfliteAssets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory

/**
 * YAMNet audio event classifier via LiteRT (port of
 * `lib/detection/audio/yamnet_audio_event_classifier.dart`).
 *
 * The bundled checkpoint has the audio front-end fused in-graph: its input is
 * the raw 16 kHz waveform ([inputSamples] samples = 0.975 s). Tensor
 * quantization (scale/zero_point) is read from the model at init, so the same
 * code serves int8 and float32 checkpoints.
 */
class YamnetClassifier private constructor(
    private val interpreter: InterpreterApi,
) : AudioEventClassifier {

    private var inputIsInt8 = false
    private var outputIsInt8 = false
    private var inScale = 1.0
    private var inZeroPoint = 0
    private var outScale = 1.0
    private var outZeroPoint = 0
    private lateinit var inputBytes: ByteArray
    private lateinit var outputBytes: ByteArray

    override val id: String get() = "yamnet"

    override suspend fun init() {
        val inTensor = interpreter.getInputTensor(0)
        val outTensor = interpreter.getOutputTensor(0)
        val inShape = inTensor.shape()
        val outShape = outTensor.shape()
        val inElements = inShape.fold(1) { acc, d -> acc * d }
        val outElements = outShape.fold(1) { acc, d -> acc * d }
        check(inElements == INPUT_SAMPLES) { "Unexpected YAMNet input shape ${inShape.contentToString()} (expected $INPUT_SAMPLES samples)" }
        check(outElements == 521) { "Unexpected YAMNet output shape ${outShape.contentToString()} (expected 521 classes)" }
        inputIsInt8 = inTensor.dataType() == DataType.INT8
        outputIsInt8 = outTensor.dataType() == DataType.INT8
        val inQ = inTensor.quantizationParams()
        val outQ = outTensor.quantizationParams()
        inScale = inQ.scale.toDouble()
        inZeroPoint = inQ.zeroPoint
        outScale = outQ.scale.toDouble()
        outZeroPoint = outQ.zeroPoint
        inputBytes = ByteArray(INPUT_SAMPLES * if (inputIsInt8) 1 else 4)
        outputBytes = ByteArray(521 * if (outputIsInt8) 1 else 4)
    }

    override suspend fun classify(window: AudioWindow): AudioEventScores {
        writeInput(inputBytes, window.samples, inputIsInt8, inScale, inZeroPoint)
        // TFLite maps byte[] to UINT8 tensors; float32 tensors need ByteBuffers.
        val input: Any = if (inputIsInt8) inputBytes
        else ByteBuffer.wrap(inputBytes).order(ByteOrder.LITTLE_ENDIAN)
        val output: Any = if (outputIsInt8) outputBytes
        else ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        interpreter.run(input, output)
        val classScores = readOutput(outputBytes, outputIsInt8, outScale, outZeroPoint)
        return AudioEventScores(
            timestamp = window.timestamp,
            classScores = scoresFromClasses(classScores, window.samples),
        )
    }

    override suspend fun dispose() {
        interpreter.close()
    }

    companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        const val LABELS_ASSET = "yamnet_labels.txt"

        /** YAMNet AudioSet class indices used for alert types. */
        const val BABY_CRY_CLASS = 20
        val GLASS_CLASSES = intArrayOf(435, 437, 463, 464)
        val DOG_BARK_CLASSES = intArrayOf(69, 70, 71, 73)  // Dog, Bark, Yip, Bow-wow
        val GROWL_CLASSES = intArrayOf(74)                    // Growling
        val CAT_AUDIO_CLASSES = intArrayOf(77, 78, 79, 80, 81) // Cat, Purr, Meow, Hiss, Caterwaul

        /** Expected input sample count for a 0.975 s patch at 16 kHz. */
        const val INPUT_SAMPLES = 15600

        /** Loads the bundled YAMNet model; null when unavailable (mock fallback). */
        fun load(context: Context): YamnetClassifier? = try {
            val model = TfliteAssets.loadModelFile(context, MODEL_ASSET)
            YamnetClassifier(
                InterpreterFactory().create(model, InterpreterApi.Options().setNumThreads(1)),
            )
        } catch (_: Exception) {
            null
        }

        /**
         * Maps the 521 class-score vector to per-type alert scores, independent
         * of the model runtime (pure, unit-testable).
         */
        fun scoresFromClasses(classScores: FloatArray, windowSamples: FloatArray): Map<String, Double> {
            var glass = 0.0
            for (c in GLASS_CLASSES) {
                if (c < classScores.size && classScores[c] > glass) glass = classScores[c].toDouble()
            }
            var dogBark = 0.0
            for (c in DOG_BARK_CLASSES) {
                if (c < classScores.size && classScores[c] > dogBark) dogBark = classScores[c].toDouble()
            }
            var growl = 0.0
            for (c in GROWL_CLASSES) {
                if (c < classScores.size && classScores[c] > growl) growl = classScores[c].toDouble()
            }
            var cat = 0.0
            for (c in CAT_AUDIO_CLASSES) {
                if (c < classScores.size && classScores[c] > cat) cat = classScores[c].toDouble()
            }
            var rms = 0.0
            for (s in windowSamples) rms += s * s
            rms = sqrt(rms / windowSamples.size)
            val loudNoise = ((rms - 0.05) / 0.15).coerceIn(0.0, 1.0)
            return mapOf(
                "baby_cry" to if (BABY_CRY_CLASS < classScores.size) classScores[BABY_CRY_CLASS].toDouble() else 0.0,
                "glass" to glass,
                "loud_noise" to loudNoise,
                "dog_bark" to dogBark,
                "growl" to growl,
                "cat" to cat,
            )
        }

        /**
         * Serializes a waveform into raw tensor bytes for [target] (int8 or
         * float32), applying the model's input quantization. Pure/testable.
         */
        fun writeInput(
            target: ByteArray,
            samples: FloatArray,
            int8: Boolean,
            scale: Double,
            zeroPoint: Int,
        ) {
            if (int8) {
                for (i in samples.indices) {
                    val q = Math.round(samples[i] / scale + zeroPoint)
                    target[i] = q.coerceIn(-128, 127).toByte()
                }
            } else {
                val view = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) view.putFloat(i * 4, samples[i])
            }
        }

        /**
         * Deserializes raw output tensor bytes into float class scores,
         * applying the model's output dequantization. Pure/testable.
         */
        fun readOutput(bytes: ByteArray, int8: Boolean, scale: Double, zeroPoint: Int): FloatArray {
            val out = FloatArray(bytes.size / if (int8) 1 else 4)
            if (int8) {
                for (i in out.indices) out[i] = (bytes[i].toInt() * scale + zeroPoint).toFloat()
            } else {
                val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in out.indices) out[i] = view.getFloat(i * 4)
            }
            return out
        }
    }
}