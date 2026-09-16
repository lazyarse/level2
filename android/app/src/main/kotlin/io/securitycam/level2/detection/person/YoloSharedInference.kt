package io.securitycam.level2.detection.person

import io.securitycam.level2.detection.ColorBitmap

/**
 * Runs the shared YOLO26n model at most once per frame.
 *
 * All six YOLO-backed engines ([YoloPersonEngine], [YoloVehicleEngine],
 * [YoloDogEngine], [YoloCatEngine], [YoloBirdEngine], [YoloLivestockEngine])
 * share one [YoloModelSingleton] model, but each used to call `run` itself —
 * N enabled detectors meant N serial ~600 ms CPU inferences per motion frame
 * ([DetectorPipeline.processFrame] runs gated detectors sequentially). Each
 * engine now routes its raw-output inference through [getOrRun]: the first
 * engine to see a frame runs the model, the rest reuse the raw output tensor
 * and only pay their own preprocess + class decode.
 *
 * Keyed on frame identity: the pipeline hands the same [ColorBitmap] instance
 * to every detector for one frame, so the cache hits within a frame and can
 * never serve a stale frame. Only the latest frame's output is retained.
 * Thread-safe via [synchronized] (the frame dispatcher is serial today, but
 * engines must not depend on that).
 *
 * The returned array is shared — callers must only read it.
 */
object YoloSharedInference {
    private var cachedFrame: ColorBitmap? = null
    private var cachedOutput: FloatArray? = null

    @Synchronized
    fun getOrRun(frame: ColorBitmap, run: () -> FloatArray): FloatArray {
        if (cachedFrame !== frame) {
            cachedOutput = run()
            cachedFrame = frame
        }
        return cachedOutput!!
    }

    /** Test seam / memory hygiene: drops the retained output. */
    @Synchronized
    fun clear() {
        cachedFrame = null
        cachedOutput = null
    }
}
