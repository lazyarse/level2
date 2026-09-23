package io.securitycam.level2.identity

import android.util.Log
import io.securitycam.level2.camera_service.CameraFrameBus
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.detection.face.FaceEmbedder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Supplies the next face-bearing analysis frame (seam for tests). */
fun interface FaceFinder {
    /** Null when no face showed up in time. */
    suspend fun nextFace(): Pair<ColorBitmap, FaceDetection>?
}

/**
 * Live-preview enrollment: grabs one face from the camera, embeds it and
 * registers the person ([enroll]) or folds another angle into an existing
 * person's centroid ([addSample]). Duplicate labels are rejected outright —
 * use [addSample] to improve an existing person's recognition.
 *
 * With a [confirm] hook the capture becomes interactive: each face found is
 * offered to the hook, and a rejected snap (or a snap with no face, via
 * [onNoFace]) loops back for another shutter press instead of failing.
 * Without [confirm] the original one-shot semantics apply (first face wins,
 * no face ⇒ failure).
 */
open class FaceEnrollmentCoordinator(
    private val store: KnownFaceStore,
    private val embedder: FaceEmbedder?,
    private val faceFinder: FaceFinder,
    private val settingsLoader: suspend () -> AppSettings,
    private val settingsSaver: suspend (AppSettings) -> Unit,
    /** Invoked with the exact frame/box used for embedding (thumbnail source). */
    private val onCapture: ((ColorBitmap, FaceDetection) -> Unit)? = null,
    /** Review gate: return false to reject the snap and capture again. */
    private val confirm: (suspend (ColorBitmap, FaceDetection) -> Boolean)? = null,
    /** Interactive mode only: a shutter snap found no face — surface inline. */
    private val onNoFace: (() -> Unit)? = null,
) {

    /** Enrolls a NEW person; fails when [label] already exists. */
    open suspend fun enroll(label: String): Result<KnownFace> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return failure("Label must not be empty")
        val settings = settingsLoader()
        if (settings.knownFaces.any { it.label.equals(trimmed, ignoreCase = true) }) {
            return failure("Name already enrolled")
        }
        val id = newId()
        return captureAndMerge(id) { KnownFace(id = id, label = trimmed) }
    }

    /** Adds another sample for an EXISTING person (multiple angles). */
    open suspend fun addSample(id: String): Result<KnownFace> {
        val existing = settingsLoader().knownFaces.firstOrNull { it.id == id }
            ?: return failure("Unknown person")
        return captureAndMerge(existing.id) { existing }
    }

    private suspend fun captureAndMerge(
        id: String,
        faceFor: () -> KnownFace,
    ): Result<KnownFace> {
        val embedder = embedder ?: return failure("Embedding model unavailable")
        while (true) {
            val found = try {
                faceFinder.nextFace()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancel parks here (shutter/review wait): must propagate so the
                // caller's catch reports "Enrollment cancelled", not a failure.
                throw e
            } catch (e: Exception) {
                return failure("Camera error: ${e.message}")
            }
            if (found == null) {
                // Interactive mode retries with an inline error; legacy fails.
                if (confirm == null) return failure("No face seen")
                onNoFace?.invoke()
                continue
            }
            val (frame, face) = found
            onCapture?.invoke(frame, face)
            if (confirm?.invoke(frame, face) == false) continue
            // TFLite failures surface as IllegalStateException from run(); report
            // them as a normal result instead of crashing the caller's snackbar
            // with a raw native message.
            val embedding = try {
                embedder.embed(frame, doubleArrayOf(face.x1, face.y1, face.x2, face.y2))
            } catch (e: Exception) {
                Log.w(TAG, "embedding failed", e)
                return failure("Embedding failed")
            } ?: return failure("Embedding failed")
            if (embedding.isEmpty()) return failure("Embedding failed")

            store.enroll(id, embedding)
            val updated = faceFor()
            // Reload so concurrent edits between capture and save are preserved.
            val current = settingsLoader()
            settingsSaver(current.copyWith(knownFaces = current.knownFaces.filterNot { it.id == id } + updated))
            return Result.success(updated)
        }
    }

    private fun newId(): String = "face_" + UUID.randomUUID().toString().substring(0, 8)

    private fun <T> failure(message: String): Result<T> = Result.failure(IllegalStateException(message))

    companion object {
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
}
