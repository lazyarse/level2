package io.securitycam.level1.detection.person

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException

/**
 * Process-wide singleton for the YOLO26n model. Both [YoloPersonEngine] and
 * [YoloDogEngine] share this single [CompiledModel] to avoid loading the
 * ~15 MB model twice and running inference twice per frame.
 *
 * Reference-counted: the model is loaded on first [acquire] and closed when
 * the last [release] is called. Thread-safe via [synchronized].
 */
object YoloModelSingleton {
    private var model: CompiledModel? = null
    private var refCount = 0

    /**
     * Returns the shared model, loading it on first call. The caller MUST
     * call [release] when done (typically in `dispose()`).
     */
    @Synchronized
    fun acquire(context: Context): CompiledModel? {
        if (model != null) {
            refCount++
            return model
        }
        model = try {
            CompiledModel.create(
                context.assets,
                MODEL_ASSET,
                CompiledModel.Options(Accelerator.CPU),
            )
        } catch (_: LiteRtException) {
            null
        }
        if (model != null) refCount++
        return model
    }

    /**
     * Decrements the reference count and closes the model when zero.
     */
    @Synchronized
    fun release() {
        if (refCount <= 0) return
        refCount--
        if (refCount == 0) {
            model?.close()
            model = null
        }
    }

    private const val MODEL_ASSET = "yolo26n_w8a32.tflite"
}
