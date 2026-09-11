package io.securitycam.level2.event

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Channel
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import io.securitycam.level2.core.TriggerEvent
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.storage.SnapshotStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 4: forward-version unknown channel types land on the misconfigured
 * status — even when a factory happens to be registered for the type — and
 * never throw out of the pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPipelineUnknownTypeTest {

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
        val sent = mutableListOf<AlertMessage>()
        override val enabled: Boolean = true
        override val settings: ChannelSettings = object : ChannelSettings() {
            override val type: String get() = this@FakeChannel.type
            override fun toJson(): Map<String, Any?> = emptyMap()
            override val secretFields: List<String> get() = emptyList()
        }
        override suspend fun send(message: AlertMessage) {
            sent.add(message)
        }
        override suspend fun sendTest() {}
        override fun validate(): String? = null
    }

    private fun pipeline(
        recorder: FakeRecorder,
        factories: Map<String, ChannelFactory>,
    ): EventPipeline = EventPipeline(
        cameraName = "Hallway",
        detectorConfigs = mapOf(
            "motion" to DetectorConfig(
                type = "motion",
                threshold = 0.5,
                persistenceFrames = 1,
                routeToChannelIds = emptyList(),
            ),
        ),
        channelConfigs = mapOf(
            "log" to ChannelConfig(id = "log", type = "log", enabled = true),
            "future" to ChannelConfig(
                id = "future",
                type = "future-type",
                enabled = true,
                settingsJson = mapOf("apiKey" to "s3cret"),
            ),
        ),
        recorder = recorder,
        snapshotStore = FakeSnapshots(),
        channelFactories = factories,
    )

    @Test
    fun unknownTypeWithRegisteredFactoryStillRecordsMisconfigured() = runBlocking {
        val recorder = FakeRecorder()
        val log = FakeChannel("log", "log")
        val future = FakeChannel("future", "future-type")
        pipeline(
            recorder,
            mapOf(
                "log" to { _: ChannelConfig -> log },
                // A factory exists, but the type has no typed settings: the
                // null settings route must win over the factory.
                "future-type" to { _: ChannelConfig -> future },
            ),
        ).handleBatch(
            TriggerBatch(base, listOf(TriggerEvent(base, "motion", 0.9, "motion"))),
        )

        val statuses = recorder.recorded.single().channelStatuses
        assertEquals(EventPipeline.STATUS_DELIVERED, statuses["log"])
        assertEquals(EventPipeline.STATUS_MISCONFIGURED, statuses["future"])
        assertTrue(future.sent.isEmpty())
    }

    @Test
    fun unknownTypeWithoutFactoryRecordsMisconfigured() = runBlocking {
        val recorder = FakeRecorder()
        val log = FakeChannel("log", "log")
        pipeline(
            recorder,
            mapOf("log" to { _: ChannelConfig -> log }),
        ).handleBatch(
            TriggerBatch(base, listOf(TriggerEvent(base, "motion", 0.9, "motion"))),
        )

        assertEquals(
            EventPipeline.STATUS_MISCONFIGURED,
            recorder.recorded.single().channelStatuses["future"],
        )
    }
}
