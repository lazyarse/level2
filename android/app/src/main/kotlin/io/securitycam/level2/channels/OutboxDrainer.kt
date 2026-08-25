package io.securitycam.level2.channels

import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import io.securitycam.level2.storage.OutboxPolicy
import io.securitycam.level2.storage.OutboxQueue

/**
 * Connectivity-independent drain over the outbox (see
 * docs/plans/2026-08-24-offline-alert-outbox-design.md). Pure delivery logic:
 * senders are injected so the WorkManager shell stays thin and the rules —
 * FIFO order, attempt counting, expiry with callbacks — are unit-testable.
 */
class OutboxDrainer(
    private val queue: OutboxQueue,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Returns true when the notification was delivered. */
    private val sendNotify: suspend (OutboxEntity) -> Boolean,
    /** Returns true when the media item was uploaded (cloud-backup phase). */
    private val sendBackup: suspend (OutboxEntity) -> Boolean = { false },
    /** Called after a row is delivered AND removed from the queue. */
    private val onDelivered: suspend (OutboxEntity) -> Unit = {},
    /** Called after an expired row is dropped from the queue. */
    private val onExpired: suspend (OutboxEntity) -> Unit = {},
) {
    /**
     * Processes one FIFO batch. Returns true when rows remain pending, so a
     * scheduler (WorkManager retry) knows to come back.
     */
    suspend fun drainOnce(): Boolean {
        val now = nowMs()
        for (row in queue.peekBatch()) {
            if (OutboxPolicy.isExpired(row, now)) {
                queue.delete(row.id)
                onExpired(row)
                continue
            }
            val ok = when (row.kind) {
                OutboxKind.NOTIFY -> sendNotify(row)
                OutboxKind.BACKUP -> sendBackup(row)
                else -> false
            }
            if (ok) {
                queue.delete(row.id)
                onDelivered(row)
            } else {
                queue.markAttempted(row.id, row.attempts + 1, now)
            }
        }
        // Rows beyond this batch (or freshly failed ones) keep the worker coming.
        return queue.peekBatch(limit = 1).isNotEmpty()
    }
}
