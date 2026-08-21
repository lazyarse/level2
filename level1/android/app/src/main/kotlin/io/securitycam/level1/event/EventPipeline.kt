package io.securitycam.level1.event

import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.Channel
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.core.TriggerEvent
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
        for (target in targets) {
            val factory = channelFactories[target.type]
            if (factory == null) continue
            val channel = factory(target)
            statuses[target.id] = sendWithRetry(channel, message)
        }

        recorder.record(
            RecordedEvent(
                timestamp = batch.timestamp,
                cameraName = cameraName,
                triggerType = type,
                triggerTypes = if (single) emptyList() else types,
                score = batch.triggers.maxOf { it.score },
                snapshotName = snapshot?.name,
                videoName = batch.videoName,
                channelStatuses = statuses,
            ),
        )
    }

    /** Sends with up to [maxAttempts] attempts, backing off between failures. */
    private suspend fun sendWithRetry(channel: Channel, message: AlertMessage): String {
        for (attempt in 0 until maxAttempts) {
            try {
                channel.send(message)
                return "delivered"
            } catch (_: Exception) {
                if (attempt == maxAttempts - 1) return "failed"
                sleep(backoffDelays[attempt])
            }
        }
        return "failed"
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
    }
}

/** Human label for a trigger type (port of `lib/event/event_pipeline.dart`). */
fun triggerLabel(triggerType: String): String = when (triggerType) {
    TriggerType.motion -> "Motion"
    TriggerType.babyCry -> "Baby crying"
    TriggerType.glassBreak -> "Glass breaking"
    TriggerType.loudNoise -> "Loud noise"
    TriggerType.merged -> "Multiple triggers"
    TriggerType.person -> "Person"
    TriggerType.face -> "Face"
    TriggerType.tamper -> "Tamper"
    TriggerType.health -> "Health"
    else -> "Activity"
}

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