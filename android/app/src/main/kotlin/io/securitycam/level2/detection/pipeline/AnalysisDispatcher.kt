package io.securitycam.level2.detection.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes [process] invocations behind a single latest-wins pending slot
 * (port of `lib/detection/analysis_dispatcher.dart`). At most one [process]
 * call is in flight at a time; adding an input while busy replaces the pending
 * slot. Errors are routed to [onError] and the loop always continues.
 */
class AnalysisDispatcher<T>(
    private val scope: CoroutineScope,
    private val process: suspend (T) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var pending: T? = null
    private var hasPending = false
    private var processing = false
    private var drainJob: Job? = null

    @Volatile
    private var disposed = false

    fun add(input: T) {
        synchronized(lock) {
            if (disposed) return
            pending = input
            hasPending = true
            if (!processing) {
                processing = true
                drainJob = scope.launch { drain() }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val input: T
            synchronized(lock) {
                if (disposed || !hasPending) {
                    processing = false
                    drainJob = null
                    return
                }
                @Suppress("UNCHECKED_CAST")
                input = pending as T
                hasPending = false
            }
            try {
                process(input)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** Clears the pending slot and stops the loop once in-flight work completes. */
    suspend fun dispose() {
        val job: Job?
        synchronized(lock) {
            disposed = true
            hasPending = false
            pending = null
            job = drainJob
        }
        job?.join()
    }
}