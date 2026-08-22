package io.securitycam.level1.identity

import io.securitycam.level1.camera_service.CameraFrameBus
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.face.FaceDetection
import io.securitycam.level1.detection.face.FaceEmbedder
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
 * Live-preview enrollment: grabs one face from the camera, embeds it, merges
 * it into the person's centroid and registers [KnownFace] in settings.
 * Re-enrolling an existing label folds into that person's centroid.
 */
open class FaceEnrollmentCoordinator(
    private val store: KnownFaceStore,
    private val embedder: FaceEmbedder?,
    private val faceFinder: FaceFinder,
    private val settingsLoader: suspend () -> AppSettings,
    private val settingsSaver: suspend (AppSettings) -> Unit,
) {

    /** Enrolls one sample for [label]; success carries the person entry. */
    open suspend fun enroll(label: String): Result<KnownFace> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return failure("Label must not be empty")
        val embedder = embedder ?: return failure("Embedding model unavailable")
        val (frame, face) = try {
            faceFinder.nextFace() ?: return failure("No face seen")
        } catch (e: Exception) {
            return failure("Camera error: ${e.message}")
        }
        val embedding = embedder.embed(frame, doubleArrayOf(face.x1, face.y1, face.x2, face.y2))
            ?: return failure("Embedding failed")
        if (embedding.isEmpty()) return failure("Embedding failed")

        val settings = settingsLoader()
        val existing = settings.knownFaces.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }
        val id = existing?.id ?: newId()
        store.enroll(id, embedding)
        val updated = existing ?: KnownFace(id = id, label = trimmed)
        val knownFaces =
            settings.knownFaces.filterNot { it.id == id } + updated
        settingsSaver(settings.copyWith(knownFaces = knownFaces))
        return Result.success(updated)
    }

    private fun newId(): String = "face_" + UUID.randomUUID().toString().substring(0, 8)

    private fun <T> failure(message: String): Result<T> = Result.failure(IllegalStateException(message))

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /**
         * Bus-driven finder: feeds published frames to a detection worker and
         * completes with the first face found; null on timeout.
         */
        fun busFinder(
            engineFactory: () -> io.securitycam.level1.detection.face.FaceEngine,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): FaceFinder = FaceFinder {
            coroutineScope {
                val engine = engineFactory()
                // DROP_OLDEST: under load we always test the freshest frame.
                val frames = Channel<ColorBitmap>(
                    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                )
                val hit = CompletableDeferred<Pair<ColorBitmap, FaceDetection>?>()
                val worker = launch(Dispatchers.IO) {
                    var result: Pair<ColorBitmap, FaceDetection>? = null
                    while (result == null) {
                        val frame = runCatching { frames.receiveCatching().getOrNull() }
                            .getOrNull() ?: break
                        val best = runCatching { engine.detectFaces(frame) }
                            .getOrDefault(emptyList())
                            .maxByOrNull { it.score } ?: continue
                        result = frame to best
                    }
                    hit.complete(result)
                }
                val listener: (ByteArray, Int, Int) -> Unit = { bgr, w, h ->
                    frames.trySend(ColorBitmap(w, h, bgr))
                }
                CameraFrameBus.add(listener)
                try {
                    engine.init()
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
