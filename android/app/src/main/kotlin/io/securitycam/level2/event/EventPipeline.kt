package io.securitycam.level2.event

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Channel
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.DetectorType
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.core.TriggerEvent
import io.securitycam.level2.channels.AlertLog
import io.securitycam.level2.channels.AlertLogEntry
import io.securitycam.level2.channels.ChannelRegistry
import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import io.securitycam.level2.storage.SnapshotStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * Per-trigger channel result. Raw [STATUS_FAILED] (not flipped to queued):
     * the caller owns the event row, flips failures to [STATUS_QUEUED], and
     * enqueues the outbox with the now-known event id.
     */
    data class SingleTriggerResult(
        val text: String,
        val type: String,
        val statuses: Map<String, String>,
        val failedTargets: List<ChannelConfig>,
    )

    /**
     * Immediate per-trigger send: saves [snapshot], routes [trigger] alone,
     * and delivers to every target. Never touches the event log or the
     * outbox — the monitoring runtime records the early row first, then
     * enqueues failures against it. Snapshot is freshly captured per trigger
     * by the caller (never the batcher's shared still).
     *
     * [onlyChannelIds] restricts delivery to the given channel ids (the
     * runtime's frequency gate); skipped channels are absent from the result
     * so the caller keeps their last status. Null means all routed targets.
     */
    suspend fun sendSingle(
        trigger: TriggerEvent,
        snapshot: Snapshot?,
        onlyChannelIds: Set<String>? = null,
    ): SingleTriggerResult {
        if (snapshot != null && onlyChannelIds?.isEmpty() != true) {
            try {
                snapshotStore.save(snapshot)
            } catch (_: Exception) {
            }
        }
        if (onlyChannelIds != null && onlyChannelIds.isEmpty()) {
            return SingleTriggerResult(
                text = buildAlertTextForTrigger(trigger),
                type = trigger.triggerType,
                statuses = emptyMap(),
                failedTargets = emptyList(),
            )
        }
        val text = buildAlertTextForTrigger(trigger)
        val message = AlertMessage(
            timestamp = trigger.timestamp,
            triggerType = trigger.triggerType,
            text = text,
            snapshot = snapshot,
        )
        val (statuses, failedTargets) = deliver(listOf(trigger), message, onlyChannelIds)
        return SingleTriggerResult(text, trigger.triggerType, statuses, failedTargets)
    }

    /**
     * Shared channel fan-out: raw FAILED statuses plus the failed targets.
     * Targets send concurrently so one slow channel (big upload, backoff
     * retries) never delays the others or the event-row merge; result order
     * still follows routing order.
     */
    private suspend fun deliver(
        triggers: List<TriggerEvent>,
        message: AlertMessage,
        onlyChannelIds: Set<String>? = null,
    ): Pair<LinkedHashMap<String, String>, MutableList<ChannelConfig>> {
        val targets = targetsFor(triggers)
            .filter { onlyChannelIds == null || it.id in onlyChannelIds }
        // Per-target result: status plus the failed config (null unless FAILED).
        val results = coroutineScope {
            targets.map { target ->
                async {
                    val factory = channelFactories[target.type]
                    if (factory == null) {
                        // No factory for this channel type (unknown type, missing
                        // build): record the hole explicitly instead of dropping the
                        // target silently from the event history.
                        return@async Triple(target.id, STATUS_MISCONFIGURED, null)
                    }
                    // Unknown (forward-version) channel types fail soft: a null typed
                    // settings means no factory can be trusted to build it, so it
                    // lands on the explicit misconfigured status instead of throwing.
                    if (ChannelRegistry.buildChannelSettings(target.type, target.settingsJson) == null) {
                        return@async Triple(target.id, STATUS_MISCONFIGURED, null)
                    }
                    val status = sendWithRetry(factory(target), message)
                    Triple(target.id, status, if (status == STATUS_FAILED) target else null)
                }
            }.awaitAll()
        }
        val statuses = LinkedHashMap<String, String>()
        val failedTargets = mutableListOf<ChannelConfig>()
        val byId = targets.associateBy { it.id }
        for ((id, status, failed) in results) {
            statuses[id] = status
            if (failed != null) failedTargets.add(byId.getValue(id))
        }
        return statuses to failedTargets
    }

    suspend fun handleBatch(batch: TriggerBatch): Long {
        val types = batch.triggers.map { it.triggerType }.distinct()
        val single = types.size == 1
        val type = alertType(batch)

        val snapshot = batch.snapshot
        if (snapshot != null) {
            try {
                snapshotStore.save(snapshot)
            } catch (_: Exception) {
            }
        }

        val text = buildAlertText(batch)
        val message = AlertMessage(
            timestamp = batch.timestamp,
            triggerType = type,
            text = text,
            snapshot = snapshot,
        )

        val (statuses, failedTargets) = deliver(batch.triggers, message)

        // Offline queueing: with an outbox wired, exhausted deliveries become
        // "queued" rows instead of permanent failures.
        if (outboxSink != null) {
            for (target in failedTargets) statuses[target.id] = STATUS_QUEUED
        }

        // Log every channel delivery to the alert log (except the log channel itself, which already logs).
        for ((channelId, status) in statuses) {
            val cfg = channelConfigs[channelId]
            if (cfg != null && cfg.type == "log") continue
            AlertLog.add(
                AlertLogEntry(
                    timestamp = batch.timestamp,
                    channelId = channelId,
                    triggerType = type,
                    text = text,
                    status = status,
                ),
            )
        }

        val eventId = recorder.record(
            RecordedEvent(
                timestamp = batch.timestamp,
                cameraName = cameraName,
                triggerType = type,
                triggerTypes = if (single) emptyList() else types,
                score = batch.triggers.maxOfOrNull { it.score } ?: 0.0,
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
        return eventId
    }

    /** Sends with up to [maxAttempts] attempts, backing off between failures. */
    private suspend fun sendWithRetry(channel: Channel, message: AlertMessage): String {
        for (attempt in 0 until maxAttempts) {
            try {
                channel.send(message)
                return STATUS_DELIVERED
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt == maxAttempts - 1) return STATUS_FAILED
                sleep(backoffDelays.getOrElse(attempt) { Duration.ZERO })
            }
        }
        return STATUS_FAILED
    }

    /**
     * Channels an alert routes to for the given batch's triggers (enabled ∪
     * log, filtered by per-detector routes). Public so the monitoring runtime
     * can derive the GIF-preview target set from the same routing rules.
     */
    fun targetsFor(triggers: List<TriggerEvent>): List<ChannelConfig> {
        val anyKnownDetector = triggers.any { detectorConfigs.containsKey(it.detectorId) }
        val anyEmptyRoutes = triggers.any { t ->
            val config = detectorConfigs[t.detectorId]
            config != null && config.routeToChannelIds.isEmpty()
        }
        return channelConfigs.values
            .filter { c -> c.enabled || c.type == "log" }
            .filter { c ->
                // Every active detector's alert is logged unconditionally:
                // the log feed is not a per-detector route option.
                (c.type == "log" && anyKnownDetector) ||
                    anyEmptyRoutes ||
                    triggers.any { t ->
                        val config = detectorConfigs[t.detectorId]
                        config != null && config.routeToChannelIds.contains(c.id)
                    }
            }
    }

    private fun alertText(batch: TriggerBatch): String {
        val types = batch.triggers.map { it.triggerType }.distinct()
        val label = if (types.size == 1) {
            tamperDetailLabel(types.first(), batch.triggers.firstOrNull()?.detail)
                ?: healthDetailLabel(batch.triggers.firstOrNull()?.detail)
                ?: triggerLabel(types.first())
        } else {
            types.joinToString(" + ") { triggerLabel(it) }
        }
        val time = ALERT_TIME_FORMAT.format(
            batch.timestamp.atZone(ZoneId.systemDefault()),
        )
        return "$label detected in $cameraName at $time"
    }

    /** Single vs merged trigger label (mirrors [handleBatch]'s type pick). */
    fun alertType(batch: TriggerBatch): String {
        val types = batch.triggers.map { it.triggerType }.distinct()
        return if (types.size == 1) types.first() else TriggerType.merged
    }

    /** Alert text for a batch (public so preview pushes caption like the alert). */
    fun buildAlertText(batch: TriggerBatch): String = alertText(batch)

    /** Alert text for one immediate trigger (same wording as a single batch). */
    fun buildAlertTextForTrigger(trigger: TriggerEvent): String {
        val label = tamperDetailLabel(trigger.triggerType, trigger.detail)
            ?: healthDetailLabel(trigger.detail)
            ?: triggerLabel(trigger.triggerType)
        val time = ALERT_TIME_FORMAT.format(
            trigger.timestamp.atZone(ZoneId.systemDefault()),
        )
        return "$label detected in $cameraName at $time"
    }

    companion object {
        /**
         * Alert timestamp: `2026-09-10 21:30:00+01:00` — space instead of
         * the ISO `T`, no fractional seconds, UTC offset kept.
         */
        val ALERT_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx")

        val defaultBackoffDelays = listOf(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
        )

        const val STATUS_DELIVERED = "delivered"
        const val STATUS_FAILED = "failed"
        const val STATUS_QUEUED = "queued"

        /** Target selected but no channel factory could build it. */
        const val STATUS_MISCONFIGURED = "misconfigured"
    }
}

/** Human label for a trigger type (port of `lib/event/event_pipeline.dart`). */
fun triggerLabel(triggerType: String): String =
    DetectorType.fromKey(triggerType)?.label ?: "Activity"

/** Tamper detail label ("Camera covered"/"Camera moved"), or null for other types. */
fun tamperDetailLabel(triggerType: String, detail: String?): String? {
    if (triggerType != TriggerType.tamper) return null
    return when (detail) {
        io.securitycam.level2.detection.TamperDetector.DETAIL_COVERED -> "Camera covered"
        io.securitycam.level2.detection.TamperDetector.DETAIL_MOVED -> "Camera moved"
        else -> null
    }
}

/** Health detail label ("Camera feed stalled"/"Camera feed recovered"), or null. */
fun healthDetailLabel(detail: String?): String? = when (detail) {
    io.securitycam.level2.detection.HealthWatchdog.DETAIL_STALL -> "Camera feed stalled"
    io.securitycam.level2.detection.HealthWatchdog.DETAIL_RECOVERED -> "Camera feed recovered"
    else -> null
}