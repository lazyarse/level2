package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap

/**
 * Runs the shared YOLO26n model at most once per frame.
 *
 * All six YOLO-backed engines ([YoloPersonEngine], [YoloVehicleEngine],
 * [YoloDogEngine], [YoloCatEngine], [YoloBirdEngine], [YoloLivestockEngine])
 * share one [YoloModelSingleton] model. Each used to preprocess its own
 * 640×640 input tensor (~4.9 MB) *and* call `run` itself — N enabled
 * detectors meant N serial ~600 ms CPU inferences plus N large transient
 * allocations per motion frame ([DetectorPipeline.processFrame] runs gated
 * detectors sequentially), saturating weak devices and churning GC. Each
 * engine now routes through [getOrRun]: the first engine to see a frame
 * builds the input tensor and runs the model; the rest reuse both and only
 * pay their own class decode.
 *
 * Keyed on frame identity: the pipeline hands the same [ColorBitmap] instance
 * to every detector for one frame, so the cache hits within a frame and can
 * never serve a stale frame. Only the latest frame's tensors are retained.
 * Thread-safe via [synchronized] (the frame dispatcher is serial today, but
 * engines must not depend on that).
 *
 * The returned array is shared — callers must only read it.
 */
object YoloSharedInference {
    private var cachedFrame: ColorBitmap? = null
    private var cachedInput: FloatArray? = null
    private var cachedOutput: FloatArray? = null

    @Synchronized
    fun getOrRun(
        frame: ColorBitmap,
        buildInput: () -> FloatArray,
        run: (FloatArray) -> FloatArray,
    ): FloatArray {
        if (cachedFrame !== frame) {
            val input = buildInput()
            cachedInput = input
            cachedOutput = run(input)
            cachedFrame = frame
        }
        return cachedOutput!!
    }

    /** Test seam / memory hygiene: drops the retained tensors. */
    @Synchronized
    fun clear() {
        cachedFrame = null
        cachedInput = null
        cachedOutput = null
    }
}
