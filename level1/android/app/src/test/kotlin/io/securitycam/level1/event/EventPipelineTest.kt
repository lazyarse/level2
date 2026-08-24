package io.securitycam.level1.event

import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.Channel
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.core.ChannelSettings
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.core.TriggerEvent
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.storage.SnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Port of `test/event_pipeline_test.dart`. */
class EventPipelineTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")

    private fun trigger(type: String, detectorId: String, score: Double = 0.7): TriggerEvent =
        TriggerEvent(timestamp = base, triggerType = type, score = score, detectorId = detectorId)

    private fun config(type: String, routes: List<String> = listOf("telegram")): DetectorConfig =
        DetectorConfig(
            type = type,
            threshold = 0.5,
            persistenceFrames = 1,
            routeToChannelIds = routes,
        )

    private fun telegramConfig(): ChannelConfig = ChannelConfig(id = "telegram", type = "telegram")

    private fun logConfig(): ChannelConfig = ChannelConfig(id = "log", type = "log")

    private fun snap(): Snapshot = Snapshot(byteArrayOf(1), "image/png", "snap.png")

    private fun batch(
        triggers: List<TriggerEvent>,
        snapshot: Snapshot? = null,
        videoName: String? = null,
    ): TriggerBatch = TriggerBatch(base, triggers, snapshot, videoName)

    private class FakeRecorder : EventRecorder {
        val recorded = mutableListOf<RecordedEvent>()
        private var nextId = 1L

        override suspend fun record(event: RecordedEvent): Long {
            recorded.add(event)
            return nextId++
        }

        override suspend fun deleteEvents(olderThan: Instant?): DeletedMedia = DeletedMedia()
    }

    private class FakeSnapshotStore : SnapshotStore {
        val saved = mutableListOf<Snapshot>()

        override suspend fun save(snapshot: Snapshot): String {
            saved.add(snapshot)
            return snapshot.name
        }

        override suspend fun load(name: String): Snapshot? = null

        override suspend fun delete(name: String) {}
    }

    /** Test channel whose send either succeeds or throws for [failures] times. */
    private class FakeChannel(
        override val id: String,
        override val type: String,
        override val enabled: Boolean = true,
        private val failures: Int = 0,
    ) : Channel {
        val sent = mutableListOf<AlertMessage>()
        private var failuresSoFar = 0

        override val settings: ChannelSettings = object : ChannelSettings() {
            override val type: String get() = this@FakeChannel.type
            override fun toJson(): Map<String, Any?> = emptyMap()
            override val secretFields: List<String> get() = emptyList()
        }

        override suspend fun send(message: AlertMessage) {
            if (failuresSoFar < failures) {
                failuresSoFar++
                throw IllegalStateException("send failed")
            }
            sent.add(message)
        }

        override suspend fun sendTest() {}

        override fun validate(): String? = null
    }

    private class PipelineBuilder {
        val recorder = FakeRecorder()
        val snapshots = FakeSnapshotStore()
        var channels: Map<String, ChannelConfig> = emptyMap()
        var detectors: Map<String, DetectorConfig> = emptyMap()
        var factories: Map<String, ChannelFactory> = emptyMap()
        var sleep: suspend (Duration) -> Unit = {}
        var outboxSink: (suspend (io.securitycam.level1.storage.OutboxEntity) -> Unit)? = null
        var nowMs: () -> Long = { 1_000L }

        fun build(): EventPipeline = EventPipeline(
            cameraName = "Hallway",
            detectorConfigs = detectors,
            channelConfigs = channels,
            recorder = recorder,
            snapshotStore = snapshots,
            channelFactories = factories,
            sleep = sleep,
            outboxSink = outboxSink,
            nowMs = nowMs,
        )
    }

    @Test
    fun detectorDetailDrivesTheAlertText() = runBlocking {
        val builder = PipelineBuilder()
        val log = FakeChannel("log", "log")
        builder.channels = mapOf("log" to logConfig())
        builder.detectors = mapOf(
            "tamper" to config("tamper", routes = listOf("log")),
            "health" to config("health", routes = listOf("log")),
        )
        builder.factories = mapOf("log" to { _: ChannelConfig -> log })
        val p = builder.build()

        p.handleBatch(
            batch(
                listOf(
                    TriggerEvent(
                        timestamp = base,
                        triggerType = TriggerType.tamper,
                        score = 0.9,
                        detectorId = "tamper",
                        detail = "covered",
                    ),
                ),
            ),
        )
        assertTrue(log.sent.single().text.startsWith("Camera covered detected in Hallway"))

        p.handleBatch(
            batch(
                listOf(
                    TriggerEvent(
                        timestamp = base,
                        triggerType = TriggerType.tamper,
                        score = 0.6,
                        detectorId = "tamper",
                        detail = "moved",
                    ),
                ),
            ),
        )
        assertTrue(log.sent[1].text.startsWith("Camera moved detected in Hallway"))

        p.handleBatch(
            batch(
                listOf(
                    TriggerEvent(
                        timestamp = base,
                        triggerType = TriggerType.health,
                        score = 1.0,
                        detectorId = "health",
                        detail = "stall",
                    ),
                ),
            ),
        )
        assertTrue(log.sent[2].text.startsWith("Camera feed stalled detected in Hallway"))

        p.handleBatch(
            batch(
                listOf(
                    TriggerEvent(
                        timestamp = base,
                        triggerType = TriggerType.health,
                        score = 1.0,
                        detectorId = "health",
                        detail = "recovered",
                    ),
                ),
            ),
        )
        assertTrue(log.sent[3].text.startsWith("Camera feed recovered detected in Hallway"))
    }

    @Test
    fun mergesTriggersRoutesOnceRecordsMergedEntryAndSavesSnapshot() = runBlocking {
        val builder = PipelineBuilder()
        val tg = FakeChannel("telegram", "telegram")
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"), "baby_cry" to config("baby_cry"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> tg })
        val p = builder.build()

        p.handleBatch(
            batch(
                listOf(trigger("motion", "motion", score = 0.5), trigger("baby_cry", "baby_cry", score = 0.9)),
                snapshot = snap(),
            ),
        )

        assertEquals(1, builder.recorder.recorded.size)
        val recorded = builder.recorder.recorded.single()
        assertEquals("merged", recorded.triggerType)
        assertEquals(listOf("motion", "baby_cry"), recorded.triggerTypes)
        assertEquals(0.9, recorded.score, 0.0)
        assertEquals("snap.png", recorded.snapshotName)
        assertEquals(1, builder.snapshots.saved.size)
        assertEquals(1, tg.sent.size)
    }

    @Test
    fun mergedBatchKeepsTheRecognisedFaceNameAsDetail() = runBlocking {
        val builder = PipelineBuilder()
        val log = FakeChannel("log", "log")
        builder.channels = mapOf("log" to logConfig())
        builder.detectors = mapOf(
            "motion" to config("motion", routes = listOf("log")),
            TriggerType.faceKnown to config(TriggerType.faceKnown, routes = listOf("log")),
        )
        builder.factories = mapOf("log" to { _: ChannelConfig -> log })
        val p = builder.build()

        val face = TriggerEvent(
            timestamp = base,
            triggerType = TriggerType.faceKnown,
            score = 0.9,
            detectorId = TriggerType.faceKnown,
            detail = "Alice",
        )
        p.handleBatch(batch(listOf(trigger("motion", "motion"), face)))

        assertEquals(1, builder.recorder.recorded.size)
        val recorded = builder.recorder.recorded.single()
        // The recognised name must survive the merge — motion carries none.
        assertEquals("Alice", recorded.detail)
        assertTrue(recorded.triggerTypes.contains(TriggerType.faceKnown))
    }

    @Test
    fun recordsTheBatchVideoNameOnTheEvent() = runBlocking {
        val builder = PipelineBuilder()
        val log = FakeChannel("log", "log")
        builder.channels = mapOf("log" to logConfig())
        builder.factories = mapOf("log" to { _: ChannelConfig -> log })
        val p = builder.build()

        p.handleBatch(
            batch(listOf(trigger("motion", "motion")), videoName = "2026-01-01_12-00-00-000_Hallway.mp4"),
        )

        assertEquals("2026-01-01_12-00-00-000_Hallway.mp4", builder.recorder.recorded.single().videoName)
    }

    @Test
    fun singleTriggerBatchRecordsItsOwnTypeWithEmptyTriggerTypes() = runBlocking {
        val builder = PipelineBuilder()
        val tg = FakeChannel("telegram", "telegram")
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> tg })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals("motion", builder.recorder.recorded.single().triggerType)
        assertTrue(builder.recorder.recorded.single().triggerTypes.isEmpty())
    }

    @Test
    fun emptyRouteToChannelIdsTargetsAllEnabledChannels() = runBlocking {
        val log = FakeChannel("log", "log")
        val tg = FakeChannel("telegram", "telegram")
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig(), "log" to logConfig())
        builder.detectors = mapOf("motion" to config("motion", routes = emptyList()))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> tg }, "log" to { _: ChannelConfig -> log })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(1, log.sent.size)
        assertEquals(1, tg.sent.size)
        assertEquals(
            mapOf("log" to "delivered", "telegram" to "delivered"),
            builder.recorder.recorded.single().channelStatuses,
        )
    }

    @Test
    fun logChannelIsAlwaysEnabledForRoutingEvenWhenDisabled() = runBlocking {
        val log = FakeChannel("log", "log")
        val builder = PipelineBuilder()
        builder.channels = mapOf("log" to ChannelConfig(id = "log", type = "log", enabled = false))
        builder.detectors = mapOf("motion" to config("motion", routes = emptyList()))
        builder.factories = mapOf("log" to { _: ChannelConfig -> log })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(1, log.sent.size)
        assertEquals(mapOf("log" to "delivered"), builder.recorder.recorded.single().channelStatuses)
    }

    @Test
    fun missingDetectorConfigContributesNothing() = runBlocking {
        val tg = FakeChannel("telegram", "telegram")
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig(), "log" to logConfig())
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> tg })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "ghost"))))

        assertTrue(builder.recorder.recorded.single().channelStatuses.isEmpty())
        assertEquals("motion", builder.recorder.recorded.single().triggerType)
    }

    @Test
    fun channelFailureRecordsAFailedStatusButStillRecordsTheEvent() = runBlocking {
        val failing = FakeChannel("telegram", "telegram", failures = 99)
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> failing })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(mapOf("telegram" to "failed"), builder.recorder.recorded.single().channelStatuses)
    }

    @Test
    fun retriesAFlakyChannelAndDeliversOnALaterAttempt() = runBlocking {
        val flaky = FakeChannel("telegram", "telegram", failures = 2)
        val sleeps = mutableListOf<Duration>()
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> flaky })
        builder.sleep = { d -> sleeps.add(d) }
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(1, flaky.sent.size) // 2 failures + 1 success
        assertEquals(mapOf("telegram" to "delivered"), builder.recorder.recorded.single().channelStatuses)
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps)
    }

    @Test
    fun exhaustedRetriesBackOffOneThenTwoSecondsAndRecordFailed() = runBlocking {
        val alwaysFails = FakeChannel("telegram", "telegram", failures = 99)
        val sleeps = mutableListOf<Duration>()
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> alwaysFails })
        builder.sleep = { d -> sleeps.add(d) }
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(mapOf("telegram" to "failed"), builder.recorder.recorded.single().channelStatuses)
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps)
    }

    @Test
    fun withOutboxWiredExhaustedDeliveryIsQueuedAndEnqueuedWithEventReference() = runBlocking {
        val alwaysFails = FakeChannel("telegram", "telegram", failures = 99)
        val queued = mutableListOf<io.securitycam.level1.storage.OutboxEntity>()
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> alwaysFails })
        builder.outboxSink = { row -> queued.add(row) }
        builder.nowMs = { 5_000L }
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion")), snapshot = snap()))

        // Status flips to "queued" on the recorded event…
        assertEquals(
            mapOf("telegram" to EventPipeline.STATUS_QUEUED),
            builder.recorder.recorded.single().channelStatuses,
        )
        // …and exactly one notify row is enqueued carrying the payload.
        val row = queued.single()
        assertEquals(io.securitycam.level1.storage.OutboxKind.NOTIFY, row.kind)
        assertEquals("telegram", row.channelId)
        assertEquals(1L, row.eventId)
        assertEquals("motion", row.triggerType)
        assertEquals(base.toEpochMilli(), row.eventTime)
        assertEquals("snap.png", row.snapshotName)
        assertEquals(5_000L, row.createdAt)
    }

    @Test
    fun deliveredChannelsAreNeverQueuedWhenOutboxIsWired() = runBlocking {
        val tg = FakeChannel("telegram", "telegram")
        val failing = FakeChannel("email", "email", failures = 99)
        val queued = mutableListOf<io.securitycam.level1.storage.OutboxEntity>()
        val builder = PipelineBuilder()
        builder.channels = mapOf(
            "telegram" to telegramConfig(),
            "email" to ChannelConfig(id = "email", type = "email"),
        )
        builder.detectors = mapOf(
            "motion" to config("motion", routes = listOf("telegram", "email")),
        )
        builder.factories = mapOf(
            "telegram" to { _: ChannelConfig -> tg },
            "email" to { _: ChannelConfig -> failing },
        )
        builder.outboxSink = { row -> queued.add(row) }
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        val statuses = builder.recorder.recorded.single().channelStatuses
        assertEquals(EventPipeline.STATUS_DELIVERED, statuses["telegram"])
        assertEquals(EventPipeline.STATUS_QUEUED, statuses["email"])
        // Only the failed channel lands in the outbox.
        assertEquals(listOf("email"), queued.map { it.channelId })
    }

    @Test
    fun withoutOutboxSinkFailuresStayFailed() = runBlocking {
        val alwaysFails = FakeChannel("telegram", "telegram", failures = 99)
        val builder = PipelineBuilder()
        builder.channels = mapOf("telegram" to telegramConfig())
        builder.detectors = mapOf("motion" to config("motion"))
        builder.factories = mapOf("telegram" to { _: ChannelConfig -> alwaysFails })
        val p = builder.build()

        p.handleBatch(batch(listOf(trigger("motion", "motion"))))

        assertEquals(
            mapOf("telegram" to EventPipeline.STATUS_FAILED),
            builder.recorder.recorded.single().channelStatuses,
        )
    }

    @Test
    fun triggerLabelMapsFace() {
        assertEquals("Face", triggerLabel(TriggerType.face))
    }
}