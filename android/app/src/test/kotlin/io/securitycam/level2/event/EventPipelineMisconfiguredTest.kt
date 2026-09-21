package io.securitycam.level2.event

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Channel
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerEvent
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.storage.SnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Wave 2: targets with no channel factory get a "misconfigured" status entry. */
class EventPipelineMisconfiguredTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")

    private class FakeRecorder : EventRecorder {
        val recorded = mutableListOf<RecordedEvent>()
        override suspend fun record(event: RecordedEvent): Long {
            recorded.add(event)
            return 1L
        }
        override suspend fun deleteEvents(olderThan: Instant?): DeletedMedia = DeletedMedia()
    }

    private class FakeSnapshots : SnapshotStore {
        override suspend fun save(snapshot: Snapshot): String = snapshot.name
        override suspend fun load(name: String): Snapshot? = null
        override suspend fun delete(name: String) {}
    }

    private class FakeChannel(
        override val id: String,
        override val type: String,
    ) : Channel {
        override val enabled: Boolean = true
        override val settings: ChannelSettings = object : ChannelSettings() {
            override val type: String get() = this@FakeChannel.type
            override fun toJson(): Map<String, Any?> = emptyMap()
            override val secretFields: List<String> get() = emptyList()
        }
        override suspend fun send(message: AlertMessage) {}

        override suspend fun sendTest() {}

        override fun validate(): String? = null
    }

    @Test
    fun missingFactoryRecordsMisconfiguredInsteadOfSkippingSilently() = runBlocking {
        val recorder = FakeRecorder()
        val pipeline = EventPipeline(
            cameraName = "Hallway",
            detectorConfigs = mapOf(
                "motion" to DetectorConfig(
                    type = "motion",
                    threshold = 0.5,
                    persistenceFrames = 1,
                ),
            ),
            channelConfigs = mapOf(
                "log" to ChannelConfig(id = "log", type = "log", enabled = true),
                "ghost" to ChannelConfig(id = "ghost", type = "no-such-type", enabled = true),
            ),
            recorder = recorder,
            snapshotStore = FakeSnapshots(),
            channelFactories = mapOf(
                "log" to { cfg: ChannelConfig -> FakeChannel(cfg.id, cfg.type) },
            ),
        )

        pipeline.handleBatch(
            TriggerBatch(
                base,
                listOf(TriggerEvent(base, "motion", 0.9, "motion")),
            ),
        )

        val statuses = recorder.recorded.single().channelStatuses
        assertEquals(EventPipeline.STATUS_DELIVERED, statuses["log"])
        assertEquals(EventPipeline.STATUS_MISCONFIGURED, statuses["ghost"])
    }
}
