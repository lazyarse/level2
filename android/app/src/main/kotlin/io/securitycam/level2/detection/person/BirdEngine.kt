package io.securitycam.level2.detection.person

import android.content.Context
import io.securitycam.level2.detection.ColorBitmap

/** Abstraction over an on-device bird detector (mirrors [DogEngine]). */
interface BirdEngine {
    suspend fun init()

    /** Returns detected birds in [frame]'s color bitmap. Empty list = none. */
    suspend fun detectBirds(frame: ColorBitmap): List<PersonBox>

    suspend fun dispose()
}

/** Test/dry-run engine: returns whatever [birds] was pre-loaded with. */
class MockBirdEngine : BirdEngine {
    val birds = mutableListOf<PersonBox>()

    override suspend fun init() {}

    override suspend fun detectBirds(frame: ColorBitmap): List<PersonBox> =
        birds.toList()

    override suspend fun dispose() {}
}

/**
 * YOLO26n bird detector via the shared [YoloModelSingleton]. Decodes COCO
 * class 14 from the same model the person detector uses — zero extra model
 * load, zero extra inference.
 */
class YoloBirdEngine(
    private val context: Context,
    private val confThreshold: Double = 0.25,
    private val iouThreshold: Double = 0.7,
    private val maxDetections: Int = 10,
) : BirdEngine {

    private var model: com.google.ai.edge.litert.CompiledModel? = null

    override suspend fun init() {
        if (model != null) return
        model = YoloModelSingleton.acquire(context)
    }

    override suspend fun detectBirds(frame: ColorBitmap): List<PersonBox> {
        val compiled = model ?: return emptyList()
        val input = buildInput(frame)
        val inputs = compiled.createInputBuffers()
        try {
            inputs[0].writeFloat(input)
            val outputs = compiled.run(inputs)
            try {
                val output = outputs[0].readFloat()
                return decodeYoloClasses(
                    output,
                    classIndices = listOf(YoloClasses.BIRD),
                    conf = confThreshold,
                    iou = iouThreshold,
                    maxDetections = maxDetections,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                )
            } finally {
                outputs.forEach { it.close() }
            }
        } finally {
            inputs.forEach { it.close() }
        }
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
