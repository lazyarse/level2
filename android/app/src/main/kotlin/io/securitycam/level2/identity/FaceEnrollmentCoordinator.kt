package io.securitycam.level2.identity

import android.util.Log
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.detection.face.FaceEmbedder
import java.util.UUID

private const val TAG = "FaceEnroll"

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
 *
 * Deliberately `open`: tests subclass to simulate canned enroll/addSample
 * outcomes (success, partial-persist failure, hangs) that the constructor
 * seams alone cannot express.
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
    /**
     * Fires once per accepted sample, after [KnownFaceStore.enroll], with the
     * merged embedding (photo-journal source; [onCapture] fires too early).
     */
    private val onEnrolled: ((String, FloatArray) -> Unit)? = null,
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
            onEnrolled?.invoke(id, embedding)
            val updated = faceFor()
            // Reload so concurrent edits between capture and save are preserved.
            val current = settingsLoader()
            settingsSaver(current.copyWith(knownFaces = current.knownFaces.filterNot { it.id == id } + updated))
            return Result.success(updated)
        }
    }

    private fun newId(): String = "face_" + UUID.randomUUID().toString().substring(0, 8)

    private fun <T> failure(message: String): Result<T> = Result.failure(IllegalStateException(message))
}
