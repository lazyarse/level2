package io.securitycam.level2.event

import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
 * Merges triggers within a sliding window into one batch (port of
 * `lib/event/trigger_batcher.dart`). Each new trigger while a batch is open
 * slides the window forward — a continuous wave stays ONE batch — until the
 * last trigger is older than [window] (or the batch exceeds
 * [maxBatchDuration], so perpetual motion can't produce a single endless
 * event). A snapshot capture starts on the first trigger of a batch; an
 * optional clip export starts too and resolves to its display name once the
 * post-roll tail is recorded. [onTriggerExtended] lets the clip keep
 * recording while the wave continues; [onBatchClose] finalizes it when the
 * wave quiets.
 */
class TriggerBatcher(
    private val scope: CoroutineScope,
    private val window: Duration,
    private val captureSnapshot: suspend () -> Snapshot?,
    private val captureVideo: suspend (Instant) -> String? = { null },
    /** Called when a trigger joins an already-open batch (wave continues). */
    private val onTriggerExtended: (() -> Unit)? = null,
    /** Called when a batch is about to close, before its captures are awaited. */
    private val onBatchClose: (() -> Unit)? = null,
    /** Hard cap on one batch's lifetime; the batch force-flushes past it. */
    private val maxBatchDuration: Duration = Duration.ofSeconds(120),
    /** Window-based fast notify: when set, the batch emits with videoName=null
     *  and the clip is linked later via this callback (batchOpenedAt, videoName?). */
    private val onVideoReady: (suspend (Instant, String?) -> Unit)? = null,
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

    /** Sliding window timer (re-armed on every trigger while a batch is open). */
    private var timer: Job? = null
    /** Hard batch-length timer (armed once per batch). */
    private var hardTimer: Job? = null
    /**
     * Batch epoch. Bumped when a batch opens (and again when it closes) so a
     * stale timer from a superseded batch — e.g. the old hard timer after a
     * new batch started — no-ops instead of flushing the wrong batch.
     */
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** Batches dropped by DROP_OLDEST backpressure (diagnostics/tests). */
    @Volatile
    var droppedBatches: Long = 0
        private set

    fun add(event: TriggerEvent) {
        if (disposed) return
        scope.launch {
            mutex.withLock {
                if (disposed) return@withLock
                if (pending.isEmpty()) {
                    openedAt = event.timestamp
                    pendingSnapshot = scope.async {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                captureSnapshot()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    pendingVideo = scope.async {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                captureVideo(event.timestamp)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    generation++
                    armBatchTimers(generation)
                } else {
                    // Wave continues: keep the clip recording and slide the
                    // flush window forward from this newest trigger.
                    try {
                        onTriggerExtended?.invoke()
                    } catch (_: Exception) {
                    }
                    armWindowTimer(generation)
                }
                pending.add(event)
            }
        }
    }

    /** (Re)arms the clean-flush timer, cancelling any pending one first. */
    private fun armWindowTimer(g: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(window.toMillis())
            flushIfCurrent(g)
        }
    }

    /** Arms both the sliding window timer and the hard batch-length timer. */
    private fun armBatchTimers(g: Long) {
        timer?.cancel()
        hardTimer?.cancel()
        timer = scope.launch {
            delay(window.toMillis())
            flushIfCurrent(g)
        }
        hardTimer = scope.launch {
            delay(maxBatchDuration.toMillis())
            flushIfCurrent(g)
        }
    }

    private suspend fun flushIfCurrent(g: Long) {
        val batch = drainIfCurrent(g) ?: return
        if (!batchFlow.tryEmit(batch)) {
            droppedBatches++
            android.util.Log.w(
                "TriggerBatcher",
                "batch dropped under backpressure (total=$droppedBatches)",
            )
        }
    }

    /**
     * Drains the current open batch (if any) without emitting it, returning
     * the [TriggerBatch] so the caller can persist it. Used by the monitor's
     * stop path: a wave interrupted mid-window must still record its event
     * (and its clip, once the in-flight export resolves). Mirrors
     * [flushIfCurrent] but hands the batch back instead of emitting.
     */
    suspend fun drainIfCurrent(): TriggerBatch? = drainIfCurrent(generation)

    private suspend fun drainIfCurrent(g: Long): TriggerBatch? {
        var events: List<TriggerEvent> = emptyList()
        var snapshotFuture: Deferred<Snapshot?>? = null
        var videoFuture: Deferred<String?>? = null
        var batchOpenedAt: Instant = Instant.EPOCH
        var hasVideo = false
        var staleTimer: Job? = null
        var staleHardTimer: Job? = null
        mutex.withLock {
            // A stale timer (its batch already closed/reopened, or superseded
            // by a newer one) must not flush the current batch.
            if (g != generation) return@withLock
            // Snapshot the timer refs before nulling — we cancel them below
            // the lock.  We must NOT cancel `timer` if it is THIS coroutine
            // (flushIfCurrent runs inside the window-timer job): cancelling
            // ourselves would throw CancellationException at the next
            // suspension point and silently drop the batch.
            staleTimer = timer
            staleHardTimer = hardTimer
            timer = null
            hardTimer = null
            if (pending.isEmpty()) return@withLock
            events = ArrayList(pending)
            // Captured local: openedAt is set exactly when the first pending
            // trigger arrives, but a null-safe fallback beats a !! crash if
            // the invariant ever breaks (uses the batch's first trigger time).
            batchOpenedAt = openedAt ?: events.first().timestamp
            snapshotFuture = pendingSnapshot
            videoFuture = pendingVideo
            hasVideo = videoFuture != null
            pending.clear()
            pendingSnapshot = null
            pendingVideo = null
            openedAt = null
            generation++  // close this batch's epoch; stale timers now no-op
        }
        if (events.isEmpty()) return null
        // Cancel stale timers that are NOT the current coroutine.  The hard
        // timer is always a different job and must be cancelled to avoid
        // leaking a coroutine (and blocking runBlocking / the runtime's
        // scope) for the full maxBatchDuration after the batch flushes.
        val currentJob = currentCoroutineContext()[Job]
        staleTimer?.takeIf { it !== currentJob }?.cancel()
        staleHardTimer?.takeIf { it !== currentJob }?.cancel()
        val drainStartMs = System.currentTimeMillis()
        runCatching {
            android.util.Log.i(
                "TriggerBatcher",
                "drain start gen=$g openedAt=$batchOpenedAt events=${events.size} hasVideo=$hasVideo",
            )
        }
        // The wave has quieted: finalize the clip (end its extended tail) so
        // the export completes instead of chaining more post-roll segments.
        if (hasVideo) {
            try {
                onBatchClose?.invoke()
            } catch (_: Exception) {
            }
        }
        val snapshot = snapshotFuture?.await()
        val snapMs = System.currentTimeMillis() - drainStartMs
        if (onVideoReady != null && hasVideo) {
            val totalMs = System.currentTimeMillis() - drainStartMs
            runCatching {
                android.util.Log.i(
                    "TriggerBatcher",
                    "drain done gen=$g events=${events.size} snapMs=$snapMs totalMs=$totalMs " +
                        "video pending (fast notify) disposed=$disposed",
                )
            }
            if (disposed) return null
            val batch = TriggerBatch(batchOpenedAt, events, snapshot, null)
            val vf = videoFuture
            val bt = batchOpenedAt
            val genCopy = g
            // Video ready must survive runtimeScope cancellation (stop() cancels
            // the batcher scope); use a process-wide scope so the late link
            // still fires after monitoring stops and crosses a restart.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                try {
                    val name = vf?.await()
                    val vidMs = System.currentTimeMillis() - drainStartMs
                    runCatching {
                        android.util.Log.i(
                            "TriggerBatcher",
                            "video ready gen=$genCopy batch=$bt video=${name ?: "null"} vidMs=$vidMs",
                        )
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        onVideoReady(bt, name)
                    }
                } catch (_: Exception) {
                }
            }
            return batch
        }
        val videoName = videoFuture?.await()
        val totalMs = System.currentTimeMillis() - drainStartMs
        runCatching {
            android.util.Log.i(
                "TriggerBatcher",
                "drain done gen=$g events=${events.size} snapMs=$snapMs totalMs=$totalMs " +
                    "videoName=${videoName ?: "null"} disposed=$disposed",
            )
        }
        // A concurrent dispose() may have run while awaiting; drop the batch.
        if (disposed) return null
        return TriggerBatch(batchOpenedAt, events, snapshot, videoName)
    }

    suspend fun dispose() {
        val snapshotFuture: Deferred<Snapshot?>?
        val videoFuture: Deferred<String?>?
        mutex.withLock {
            disposed = true
            timer?.cancel()
            timer = null
            hardTimer?.cancel()
            hardTimer = null
            snapshotFuture = pendingSnapshot
            videoFuture = pendingVideo
            pendingSnapshot = null
            pendingVideo = null
            pending.clear()
            openedAt = null
        }
        // Cancel in-flight captures so a stuck camera/export can't leak past
        // disposal; flush() already snapshotted its futures above.
        runCatching { snapshotFuture?.cancel() }
        runCatching { videoFuture?.cancel() }
    }
}