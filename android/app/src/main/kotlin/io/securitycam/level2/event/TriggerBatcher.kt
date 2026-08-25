package io.securitycam.level2.event

import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** A merged batch of triggers plus one snapshot/clip, emitted per window. */
data class TriggerBatch(
    val timestamp: Instant,
    val triggers: List<TriggerEvent>,
    val snapshot: Snapshot? = null,
    val videoName: String? = null,
)

/**
 * Merges triggers within a window into one batch (port of
 * `lib/event/trigger_batcher.dart`). A snapshot capture starts on the first
 * trigger of a batch; an optional clip export starts too and resolves to its
 * display name once the post-roll tail is recorded.
 */
class TriggerBatcher(
    private val scope: CoroutineScope,
    private val window: Duration,
    private val captureSnapshot: suspend () -> Snapshot?,
    private val captureVideo: suspend (Instant) -> String? = { null },
) {
    private val batchFlow = MutableSharedFlow<TriggerBatch>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val batches: Flow<TriggerBatch> get() = batchFlow.asSharedFlow()

    private val mutex = Mutex()
    private var openedAt: Instant? = null
    private val pending = ArrayList<TriggerEvent>()
    private var pendingSnapshot: Deferred<Snapshot?>? = null
    private var pendingVideo: Deferred<String?>? = null
    private var timer: Job? = null

    @Volatile
    private var disposed = false

    fun add(event: TriggerEvent) {
        if (disposed) return
        scope.launch {
            mutex.withLock {
                if (disposed) return@withLock
                if (pending.isEmpty()) {
                    openedAt = event.timestamp
                    pendingSnapshot = scope.async {
                        try {
                            captureSnapshot()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    pendingVideo = scope.async {
                        try {
                            captureVideo(event.timestamp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    timer = scope.launch {
                        delay(window.toMillis())
                        flush()
                    }
                }
                pending.add(event)
            }
        }
    }

    private suspend fun flush() {
        val events: List<TriggerEvent>
        val snapshotFuture: Deferred<Snapshot?>?
        val videoFuture: Deferred<String?>?
        val batchOpenedAt: Instant
        mutex.withLock {
            // flush() runs INSIDE the timer coroutine, so cancelling `timer`
            // here would cancel this very coroutine: the next real suspension
            // point (awaiting a capture below) would throw
            // CancellationException and silently drop the batch. Just drop
            // the reference; dispose() cancels a still-pending timer.
            timer = null
            if (pending.isEmpty()) return
            batchOpenedAt = openedAt!!
            events = ArrayList(pending)
            snapshotFuture = pendingSnapshot
            videoFuture = pendingVideo
            pending.clear()
            pendingSnapshot = null
            pendingVideo = null
            openedAt = null
        }
        val snapshot = snapshotFuture?.await()
        val videoName = videoFuture?.await()
        // A concurrent dispose() may have run while awaiting; drop the batch.
        if (disposed) return
        batchFlow.tryEmit(TriggerBatch(batchOpenedAt, events, snapshot, videoName))
    }

    suspend fun dispose() {
        mutex.withLock {
            disposed = true
            timer?.cancel()
            timer = null
        }
    }
}