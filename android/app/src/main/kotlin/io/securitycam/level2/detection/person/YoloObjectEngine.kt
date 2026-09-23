package io.securitycam.level2.detection.person

import android.content.Context
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectedBox

/** Abstraction over the on-device YOLO object detector. */
interface YoloObjectEngine {
    suspend fun init()

    /** Returns detected boxes of the configured classes in [frame]. */
    suspend fun detect(frame: ColorBitmap): List<DetectedBox>

    suspend fun dispose()
}

/** Holds the application context so detector factories can build engines lazily. */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    fun require(): Context = checkNotNull(context) { "AppContextHolder not initialized" }
}

/**
 * YOLO26n (`yolo26n_w8a32.tflite`) via the shared [YoloModelSingleton].
 * The `format=litert` export targets the Next runtime. Preprocesses the BGR
 * [ColorBitmap] to a 640x640 RGB NCHW float32 tensor, runs inference, and
 * decodes + NMSes boxes for [classIndices]. One class serves every detector
 * (person, dog, cat, vehicle, bird, livestock differ only in classes).
 */
class YoloObjectEngineImpl(
    private val context: Context,
    private val classIndices: List<Int>,
    private val confThreshold: Double = 0.25,
    private val iouThreshold: Double = 0.7,
    private val maxDetections: Int = 10,
) : YoloObjectEngine {

    private var model: com.google.ai.edge.litert.CompiledModel? = null

    override suspend fun init() {
        if (model != null) return
        model = YoloModelSingleton.acquire(context)
    }

    override suspend fun detect(frame: ColorBitmap): List<DetectedBox> {
        val compiled = model ?: return emptyList()
        // Shared with the other YOLO engines: the first engine to see this
        // frame builds the input tensor and runs the model, the rest reuse
        // both and only pay their own class decode.
        val output = YoloSharedInference.getOrRun(
            frame = frame,
            buildInput = { buildInput(frame) },
        ) { input ->
            val inputs = compiled.createInputBuffers()
            try {
                inputs[0].writeFloat(input)
                val outputs = compiled.run(inputs)
                try {
                    outputs[0].readFloat()
                } finally {
                    outputs.forEach { it.close() }
                }
            } finally {
                inputs.forEach { it.close() }
            }
        }
        return decodeYoloClasses(
            output,
            classIndices = classIndices,
            conf = confThreshold,
            iou = iouThreshold,
            maxDetections = maxDetections,
            frameWidth = frame.width,
            frameHeight = frame.height,
        )
    }

    /** Letterboxes [frame] into the 640x640 RGB NCHW float32 input tensor. */
    private fun buildInput(frame: ColorBitmap): FloatArray {
        val info = letterboxInfo(frame.width, frame.height)
        val input = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        val bgr = frame.bgr
        for (y in 0 until INPUT_SIZE) {
            val sy = (y - info.padY) / info.gain
            if (sy < 0 || sy >= frame.height) continue
            val syi = sy.toInt()
            for (x in 0 until INPUT_SIZE) {
                val sx = (x - info.padX) / info.gain
                if (sx < 0 || sx >= frame.width) continue
                val src = (syi * frame.width + sx.toInt()) * 3
                val px = y * INPUT_SIZE + x
                input[px] = (bgr[src + 2].toInt() and 0xFF) / 255f
                input[plane + px] = (bgr[src + 1].toInt() and 0xFF) / 255f
                input[2 * plane + px] = (bgr[src].toInt() and 0xFF) / 255f
            }
        }
        return input
    }

    override suspend fun dispose() {
        model = null
        YoloModelSingleton.release()
    }

    companion object {
        const val INPUT_SIZE = 640
    }
}
