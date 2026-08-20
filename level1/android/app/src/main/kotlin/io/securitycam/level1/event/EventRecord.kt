package io.securitycam.level1.event

import java.time.Instant

/** A recorded trigger event row (port of `lib/storage/event_recorder.dart`). */
data class RecordedEvent(
    val timestamp: Instant,
    val cameraName: String,
    val triggerType: String,
    val score: Double,
    val snapshotName: String? = null,
    val videoName: String? = null,
    val channelStatuses: Map<String, String> = emptyMap(),
    val triggerTypes: List<String> = emptyList(),
)

/** Names of media files referenced by deleted event rows. */
data class DeletedMedia(
    val snapshotNames: List<String> = emptyList(),
    val videoNames: List<String> = emptyList(),
)

/** Event persistence contract. */
interface EventRecorder {
    suspend fun record(event: RecordedEvent)

    suspend fun deleteEvents(olderThan: Instant?): DeletedMedia
}