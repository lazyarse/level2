package io.securitycam.level2.event

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
    /** Free-text trigger payload (e.g. recognised face name); may be null. */
    val detail: String? = null,
)

/** Names of media files referenced by deleted event rows. */
data class DeletedMedia(
    val snapshotNames: List<String> = emptyList(),
    val videoNames: List<String> = emptyList(),
)

/** Event persistence contract. */
interface EventRecorder {
    /** Stores the event and returns its row id (for outbox back-references). */
    suspend fun record(event: RecordedEvent): Long

    suspend fun deleteEvents(olderThan: Instant?): DeletedMedia
}