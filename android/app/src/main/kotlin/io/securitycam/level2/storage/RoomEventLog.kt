package io.securitycam.level2.storage

import android.util.Log
import io.securitycam.level2.event.DeletedMedia
import io.securitycam.level2.event.EventRecorder
import io.securitycam.level2.event.RecordedEvent
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [EventRecorder] (port of `lib/storage/event_log.dart`,
 * schema version 5).
 */
class RoomEventLog(private val dao: EventDao) : EventRecorder {

    override suspend fun record(event: RecordedEvent): Long =
        dao.insert(
            EventEntity(
                timestamp = event.timestamp.toString(),
                cameraName = event.cameraName,
                triggerType = event.triggerType,
                score = event.score,
                snapshotName = event.snapshotName,
                videoName = event.videoName,
                channelStatuses = jsonEncodeChannelStatusMap(event.channelStatuses),
                triggerTypes = if (event.triggerTypes.isEmpty()) null else jsonEncodeChannelStatusList(event.triggerTypes),
                detail = event.detail,
            ),
        )

    /**
     * Late-delivery bookkeeping for the offline outbox: flips one channel's
     * status on an already-recorded event (e.g. "queued" → "delivered").
     */
    suspend fun flipChannelStatus(eventId: Long, channelId: String, status: String) {
        dao.flipChannelStatusJson(eventId, channelId, status)
    }

    /** Window-based fast notify: links the clip after the mux finishes. */
    suspend fun updateVideoName(eventId: Long, videoName: String) {
        dao.updateVideoName(eventId, videoName)
    }

    override suspend fun deleteEvents(olderThan: Instant?): DeletedMedia {
        // deleteEventsCollectingRefs already deletes the rows atomically;
        // the extra delete below the original code had was dead weight.
        val refs: List<EventDao.MediaRef> = dao.deleteEventsCollectingRefs(olderThan?.toString())
        return DeletedMedia(
            snapshotNames = refs.mapNotNull { it.snapshot_name },
            videoNames = refs.mapNotNull { it.video_name },
        )
    }

    /** Ids of rows about to be purged (for outbox notify cleanup). */
    suspend fun idsForPurge(olderThan: Instant?): List<Long> {
        val iso = olderThan?.toString()
        return if (iso == null) dao.allIds() else dao.idsOlderThan(iso)
    }

    /** Most recent rows, newest first. */
    suspend fun recent(limit: Int = 100): List<RecordedEventRow> =
        dao.recent(limit).mapNotNull { it.toRowOrNull() }

    fun recentFlow(limit: Int = 100): Flow<List<RecordedEventRow>> =
        dao.recentFlow(limit).map { rows -> rows.mapNotNull { it.toRowOrNull() } }

    /** Row-count changes; consumed by the events UI for live refresh. */
    fun countFlow(): Flow<Long> = dao.countFlow()

    /** Oldest stored event instant, or null when the log is empty. */
    suspend fun oldestInstant(): Instant? =
        dao.oldestTimestamp()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /**
     * Events with [start] <= timestamp < [end], newest first (port of the
     * planned `SqliteEventLog.between`); [withSnapshots] keeps only rows that
     * carry a snapshot for the gallery grid.
     */
    suspend fun between(
        start: Instant,
        end: Instant,
        limit: Int = 500,
        withSnapshots: Boolean = false,
    ): List<RecordedEventRow> {
        val startIso = start.toString()
        val endIso = end.toString()
        val rows = if (withSnapshots) {
            dao.betweenWithSnapshots(startIso, endIso, limit)
        } else {
            dao.between(startIso, endIso, limit)
        }
        return rows.mapNotNull { it.toRowOrNull() }
    }

    /** Null (and logged) when the row's timestamp is corrupt; keeps one bad row from breaking the whole list. */
    private fun EventEntity.toRowOrNull(): RecordedEventRow? = try {
        toRow()
    } catch (t: Exception) {
        Log.w("RoomEventLog", "skipping event row $id with bad timestamp", t)
        null
    }

    private fun EventEntity.toRow(): RecordedEventRow = RecordedEventRow(
        id = id,
        timestamp = Instant.parse(timestamp),
        cameraName = cameraName,
        triggerType = triggerType,
        score = score,
        snapshotName = snapshotName,
        videoName = videoName,
        channelStatuses = channelStatuses?.let(::decodeChannelStatusMap) ?: emptyMap(),
        triggerTypes = triggerTypes?.let(::decodeChannelStatusList) ?: emptyList(),
        detail = detail,
    )

}

/** Channel-status JSON helpers shared by [EventDao] defaults and [RoomEventLog]. */
internal fun jsonEncodeChannelStatusMap(m: Map<String, String>): String =
    org.json.JSONObject().apply {
        for ((k, v) in m) put(k, v)
    }.toString()

internal fun jsonEncodeChannelStatusList(l: List<String>): String =
    org.json.JSONArray().apply {
        for (s in l) put(s)
    }.toString()

internal fun decodeChannelStatusMap(raw: String): Map<String, String> = try {
    org.json.JSONObject(raw).let { o ->
        buildMap {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, o.getString(k))
            }
        }
    }
} catch (_: Exception) {
    emptyMap()
}

internal fun decodeChannelStatusList(raw: String): List<String> = try {
    val arr = org.json.JSONArray(raw)
    (0 until arr.length()).map { arr.getString(it) }
} catch (_: Exception) {
    emptyList()
}

/** A recorded event row as returned by [RoomEventLog.recent]. */
data class RecordedEventRow(
    val id: Long,
    val timestamp: Instant,
    val cameraName: String,
    val triggerType: String,
    val score: Double,
    val snapshotName: String?,
    val videoName: String?,
    val channelStatuses: Map<String, String>,
    val triggerTypes: List<String>,
    /** Free-text trigger payload (e.g. recognised face name); may be null. */
    val detail: String? = null,
)
