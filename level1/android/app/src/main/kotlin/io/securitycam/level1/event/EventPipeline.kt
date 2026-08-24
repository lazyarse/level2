package io.securitycam.level1.event

import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.Channel
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.core.DetectorType
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.core.TriggerEvent
import io.securitycam.level1.storage.OutboxEntity
import io.securitycam.level1.storage.OutboxKind
import io.securitycam.level1.storage.SnapshotStore
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Factory building a channel from its config (port of `lib/core/registries.dart`). */
typealias ChannelFactory = (ChannelConfig) -> Channel

/**
 * Turns a [TriggerBatch] into stored events and channel alerts (port of
 * `lib/event/event_pipeline.dart`). Routing = enabled channels ∩ trigger-type
 * routes; empty routes → all enabled channels plus log. Per-channel retry with
 * backoff; merged events carry the trigger-types list.
 */
class EventPipeline(
    private val cameraName: String,
    private val detectorConfigs: Map<String, DetectorConfig>,
    private val channelConfigs: Map<String, ChannelConfig>,
    private val recorder: EventRecorder,
    private val snapshotStore: SnapshotStore,
    private val channelFactories: Map<String, ChannelFactory> = emptyMap(),
    private val maxAttempts: Int = 3,
    private val backoffDelays: List<Duration> = defaultBackoffDelays,
    private val sleep: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    /**
     * When wired, a delivery that exhausts its retries is persisted to the
     * offline outbox (status "queued") instead of being dropped ("failed").
     * Production wires the Room-backed store; tests pass a capture lambda.
     */
    private val outboxSink: (suspend (OutboxEntity) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun handleBatch(batch: TriggerBatch) {
        val types = batch.triggers.map { it.triggerType }.distinct()
        val single = types.size == 1
        val type = if (single) types.first() else TriggerType.merged

        val snapshot = batch.snapshot
        if (snapshot != null) {
            try {
                snapshotStore.save(snapshot)
            } catch (_: Exception) {
            }
        }

        val text = alertText(batch, snapshot)
        val message = AlertMessage(
            timestamp = batch.timestamp,
            triggerType = type,
            text = text,
            snapshot = snapshot,
        )

        val targets = targetsFor(batch.triggers)

        val statuses = LinkedHashMap<String, String>()
        val failedTargets = mutableListOf<ChannelConfig>()
        for (target in targets) {
            val factory = channelFactories[target.type]
            if (factory == null) continue
            val status = sendWithRetry(factory(target), message)
            statuses[target.id] = status
            if (status == STATUS_FAILED) failedTargets.add(target)
        }

        // Offline queueing: with an outbox wired, exhausted deliveries become
        // "queued" rows instead of permanent failures.
        if (outboxSink != null) {
            for (target in failedTargets) statuses[target.id] = STATUS_QUEUED
        }

        val eventId = recorder.record(
            RecordedEvent(
                timestamp = batch.timestamp,
                cameraName = cameraName,
                triggerType = type,
                triggerTypes = if (single) emptyList() else types,
                score = batch.triggers.maxOf { it.score },
                snapshotName = snapshot?.name,
                videoName = batch.videoName,
                channelStatuses = statuses,
                // Merged batches mix detail-less triggers (motion) with
                // detail-bearing ones (face_known): prefer any real payload.
                detail = batch.triggers
                    .firstOrNull { !it.detail.isNullOrBlank() }?.detail,
            ),
        )

        if (outboxSink != null && failedTargets.isNotEmpty()) {
            for (target in failedTargets) {
                outboxSink.invoke(
                    OutboxEntity(
                        createdAt = nowMs(),
                        kind = OutboxKind.NOTIFY,
                        channelId = target.id,
                        eventId = eventId,
                        triggerType = type,
                        eventTime = batch.timestamp.toEpochMilli(),
                        text = text,
                        snapshotName = snapshot?.name,
                    ),
                )
            }
        }
    }

    /** Sends with up to [maxAttempts] attempts, backing off between failures. */
    private suspend fun sendWithRetry(channel: Channel, message: AlertMessage): String {
        for (attempt in 0 until maxAttempts) {
            try {
                channel.send(message)
                return STATUS_DELIVERED
            } catch (_: Exception) {
                if (attempt == maxAttempts - 1) return STATUS_FAILED
                sleep(backoffDelays[attempt])
            }
        }
        return STATUS_FAILED
    }

    private fun targetsFor(triggers: List<TriggerEvent>): List<ChannelConfig> {
        val anyEmptyRoutes = triggers.any { t ->
            val config = detectorConfigs[t.detectorId]
            config != null && config.routeToChannelIds.isEmpty()
        }
        return channelConfigs.values
            .filter { c -> c.enabled || c.type == "log" }
            .filter { c ->
                anyEmptyRoutes ||
                    triggers.any { t ->
                        val config = detectorConfigs[t.detectorId]
                        config != null && config.routeToChannelIds.contains(c.id)
                    }
            }
    }

    private fun alertText(
        batch: TriggerBatch,
        snapshot: Snapshot?,
    ): String {
        val types = batch.triggers.map { it.triggerType }.distinct()
        val label = if (types.size == 1) {
            tamperDetailLabel(types.first(), batch.triggers.firstOrNull()?.detail)
                ?: healthDetailLabel(batch.triggers.firstOrNull()?.detail)
                ?: triggerLabel(types.first())
        } else {
            types.joinToString(" + ") { triggerLabel(it) }
        }
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            batch.timestamp.atZone(ZoneId.systemDefault()),
        )
        return "$label detected in $cameraName at $time"
    }

    companion object {
        val defaultBackoffDelays = listOf(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
        )

        const val STATUS_DELIVERED = "delivered"
        const val STATUS_FAILED = "failed"
        const val STATUS_QUEUED = "queued"
    }
}

/** Human label for a trigger type (port of `lib/event/event_pipeline.dart`). */
fun triggerLabel(triggerType: String): String =
    DetectorType.fromKey(triggerType)?.label ?: "Activity"

/** Tamper detail label ("Camera covered"/"Camera moved"), or null for other types. */
fun tamperDetailLabel(triggerType: String, detail: String?): String? {
    if (triggerType != TriggerType.tamper) return null
    return when (detail) {
        io.securitycam.level1.detection.TamperDetector.DETAIL_COVERED -> "Camera covered"
        io.securitycam.level1.detection.TamperDetector.DETAIL_MOVED -> "Camera moved"
        else -> null
    }
}

/** Health detail label ("Camera feed stalled"/"Camera feed recovered"), or null. */
fun healthDetailLabel(detail: String?): String? = when (detail) {
    io.securitycam.level1.detection.HealthWatchdog.DETAIL_STALL -> "Camera feed stalled"
    io.securitycam.level1.detection.HealthWatchdog.DETAIL_RECOVERED -> "Camera feed recovered"
    else -> null
}