package io.securitycam.level2.monitor

import io.securitycam.level2.core.ChannelConfig
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Process-scoped pending video links so a stop/restart doesn't orphan the late link. */
object VideoLinkRegistry {
    data class PendingVideoInfo(
        val eventId: Long,
        val text: String,
        val triggerType: String,
        val previewTargets: List<ChannelConfig>,
    )

    private val pending = ConcurrentHashMap<Instant, PendingVideoInfo>()

    fun put(batchTime: Instant, info: PendingVideoInfo) {
        pending[batchTime] = info
    }

    fun remove(batchTime: Instant): PendingVideoInfo? = pending.remove(batchTime)

    fun contains(batchTime: Instant): Boolean = pending.containsKey(batchTime)

    fun pendingKeys(): Set<Instant> = pending.keys

    fun pruneOlderThan(cutoff: Instant) {
        val iter = pending.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key.isBefore(cutoff)) iter.remove()
        }
    }

    fun clear() = pending.clear()

    fun size(): Int = pending.size
}
