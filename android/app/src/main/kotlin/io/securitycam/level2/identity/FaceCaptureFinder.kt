package io.securitycam.level2.identity

import android.util.Log
import io.securitycam.level2.camera_service.CameraFrameBus
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shutter-gated [FaceFinder] factory, split out of [FaceEnrollmentCoordinator]
 * (whose capture/merge core it dwarfed).
 */
object FaceCaptureFinder {
    private const val TAG = "FaceEnroll"

    /** Post-shutter grab budget: frames arrive every 250 ms on the bus. */
    const val CAPTURE_TIMEOUT_MS = 2_000L

        /**
         * Shutter-gated finder: parks on [awaitShutter] first (no engine, no
         * bus subscription — zero CPU while the user lines up the shot), then
         * grabs the freshest frame and runs a single detection pass. Null when
         * that frame has no face (or none arrives within [timeoutMs]).
         */
        fun captureOnDemandFinder(
            engineFactory: () -> io.securitycam.level2.detection.face.FaceEngine,
            awaitShutter: suspend () -> Unit,
            timeoutMs: Long = CAPTURE_TIMEOUT_MS,
        ): FaceFinder = FaceFinder {
            awaitShutter()
            coroutineScope {
                val engine = engineFactory()
                // DROP_OLDEST: under load we always test the freshest frame.
                val frames = Channel<ColorBitmap>(
                    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                )
                val hit = CompletableDeferred<Pair<ColorBitmap, FaceDetection>?>()
                val worker = launch(Dispatchers.IO) {
                    val frame = runCatching { frames.receiveCatching().getOrNull() }.getOrNull()
                    val result = if (frame == null) {
                        null
                    } else {
                        val best = runCatching { engine.detectFaces(frame) }
                            .getOrDefault(emptyList())
                            .maxByOrNull { it.score }
                        if (best == null) null else frame to best
                    }
                    hit.complete(result)
                }
                val listener: (ByteArray, Int, Int) -> Unit = { bgr, w, h ->
                    frames.trySend(ColorBitmap(w, h, bgr))
                }
                try {
                    engine.init()
                    CameraFrameBus.add(listener)
                    try {
                        withTimeout(timeoutMs) { hit.await() }
                    } catch (_: TimeoutCancellationException) {
                        null
                    }
                } finally {
                    CameraFrameBus.remove(listener)
                    frames.close()
                    worker.cancel()
                    runCatching { engine.dispose() }
                }
            }
        }
}
