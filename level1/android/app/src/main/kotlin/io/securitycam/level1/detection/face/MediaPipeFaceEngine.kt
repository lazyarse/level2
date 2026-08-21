package io.securitycam.level1.detection.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

/**
 * BlazeFace via MediaPipe Tasks (port of `tflite_face_engine.dart`; the plan's
 * preferred engine). The bundled short-range model is the MediaPipe-published
 * float16 checkpoint; on-device output parity vs the reference back-camera
 * model is decided in Phase 7 integration tests.
 */
class MediaPipeFaceEngine(
    private val context: Context,
    private val minScore: Double = 0.5,
) : FaceEngine {

    private var detector: FaceDetector? = null

    override suspend fun init() {
        if (detector != null) return
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(minScore.toFloat())
            .build()
        detector = FaceDetector.createFromOptions(context, options)
    }

    override suspend fun detectFaces(frame: io.securitycam.level1.detection.ColorBitmap): List<FaceDetection> {
        val d = detector ?: return emptyList()
        val result = d.detect(BitmapImageBuilder(toBitmap(frame)).build())
        return result.detections().map { det ->
            val bb = det.boundingBox()
            FaceDetection(
                x1 = bb.left.toDouble(),
                y1 = bb.top.toDouble(),
                x2 = bb.right.toDouble(),
                y2 = bb.bottom.toDouble(),
                score = det.categories().firstOrNull()?.score()?.toDouble() ?: 0.0,
            )
        }
    }

    private fun toBitmap(frame: io.securitycam.level1.detection.ColorBitmap): Bitmap {
        val pixels = IntArray(frame.width * frame.height)
        for (i in pixels.indices) {
            val b = frame.bgr[i * 3].toInt() and 0xFF
            val g = frame.bgr[i * 3 + 1].toInt() and 0xFF
            val r = frame.bgr[i * 3 + 2].toInt() and 0xFF
            pixels[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    override suspend fun dispose() {
        detector?.close()
        detector = null
    }

    companion object {
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
    }
}