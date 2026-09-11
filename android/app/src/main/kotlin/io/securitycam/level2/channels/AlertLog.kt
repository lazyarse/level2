package io.securitycam.level2.channels

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One captured alert-log line (what the log channel delivered). */
data class AlertLogEntry(
    val timestamp: Instant,
    val channelId: String,
    val triggerType: String,
    val text: String,
)

/**
 * Process-wide capped alert log backing the log channel and its in-app
 * viewer. In-memory by design (the persisted Events tab remains the
 * across-restarts record); the cap bounds memory, the mutex guards
 * concurrent pipeline sends.
 */
object AlertLog {
    const val MAX_ENTRIES = 200

    private val mutex = Mutex()
    private val entries = ArrayDeque<AlertLogEntry>()
    private val _flow = MutableStateFlow<List<AlertLogEntry>>(emptyList())
    val flow: StateFlow<List<AlertLogEntry>> = _flow.asStateFlow()

    suspend fun add(entry: AlertLogEntry) {
        mutex.withLock {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            _flow.value = entries.toList()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            _flow.value = emptyList()
        }
    }
}
