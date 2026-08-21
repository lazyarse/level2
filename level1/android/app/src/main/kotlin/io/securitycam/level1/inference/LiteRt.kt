package io.securitycam.level1.inference

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterFactory

/**
 * LiteRT wrapper exposing both runtimes the app needs:
 * - classic [InterpreterApi] for YAMNet (float32 audio model)
 * - LiteRT Next [CompiledModel] for YOLO26n w8a32 (weight-only quantized)
 *
 * Model load failures leave the corresponding engine null so callers can fall
 * back to mock classifiers instead of crashing.
 */
class LiteRt private constructor(
    private val yamnet: InterpreterApi?,
    private val yolo: CompiledModel?,
) : AutoCloseable {

    companion object {
        /** 1 x 3 x 640 x 640 NCHW float32 input elements. */
        const val YOLO_INPUT_ELEMENTS: Int = 1 * 3 * 640 * 640

        fun create(context: Context): LiteRt {
            val yamnet = try {
                InterpreterFactory().create(
                    TfliteAssets.loadModelFile(context, "yamnet.tflite"),
                    InterpreterApi.Options().setNumThreads(1),
                )
            } catch (_: Exception) {
                null
            }
            val yolo = try {
                CompiledModel.create(
                    context.assets,
                    "yolo26n_w8a32.tflite",
                    CompiledModel.Options(Accelerator.CPU),
                )
            } catch (_: LiteRtException) {
                null
            }
            return LiteRt(yamnet, yolo)
        }
    }

    /** Runs YAMNet: [15600] float32 waveform → [521] class scores. */
    fun classifyYamnet(input: FloatArray): FloatArray? {
        val interpreter = yamnet ?: return null
        val output = arrayOf(FloatArray(YAMNET_CLASSES))
        interpreter.run(arrayOf(input), output)
        return output[0]
    }

    /**
     * Runs YOLO26n on a zero-filled 640x640 input → flattened `[1, 84, 8400]`
     * predictions (705,600 floats).
     */
    fun detectYoloZero(): FloatArray? {
        val compiled = yolo ?: return null
        val inputs = compiled.createInputBuffers()
        try {
            inputs[0].writeFloat(FloatArray(YOLO_INPUT_ELEMENTS))
            val outputs = compiled.run(inputs)
            try {
                return outputs[0].readFloat()
            } finally {
                outputs.forEach { it.close() }
            }
        } finally {
            inputs.forEach { it.close() }
        }
    }

    /** Runs YOLO26n on caller-provided NCHW float32 input. */
    fun detectYolo(input: FloatArray): FloatArray? {
        val compiled = yolo ?: return null
        require(input.size == YOLO_INPUT_ELEMENTS) { "expected $YOLO_INPUT_ELEMENTS floats" }
        val inputs = compiled.createInputBuffers()
        try {
            inputs[0].writeFloat(input)
            val outputs = compiled.run(inputs)
            try {
                return outputs[0].readFloat()
            } finally {
                outputs.forEach { it.close() }
            }
        } finally {
            inputs.forEach { it.close() }
        }
    }

    val yamnetAvailable: Boolean get() = yamnet != null
    val yoloAvailable: Boolean get() = yolo != null

    override fun close() {
        yamnet?.close()
        yolo?.close()
    }
}

private const val YAMNET_CLASSES = 521