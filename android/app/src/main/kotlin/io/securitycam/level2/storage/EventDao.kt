package io.securitycam.level2.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Room row for a recorded trigger event (schema v5 of the Dart event log). */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: String,
    @ColumnInfo(name = "camera_name") val cameraName: String,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "snapshot_name") val snapshotName: String?,
    @ColumnInfo(name = "video_name") val videoName: String?,
    @ColumnInfo(name = "channel_statuses") val channelStatuses: String?,
    @ColumnInfo(name = "trigger_types") val triggerTypes: String?,
    @ColumnInfo(name = "detail") val detail: String? = null,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): kotlinx.coroutines.flow.Flow<List<EventEntity>>

    /** Emits the row count on every insert/delete; drives live UI refreshes. */
    @Query("SELECT COUNT(*) FROM events")
    fun countFlow(): kotlinx.coroutines.flow.Flow<Long>

    /** Day-scoped query for the history timeline; bounds are [start, end). */
    @Query(
        "SELECT * FROM events WHERE timestamp >= :startIso AND timestamp < :endIso " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun between(startIso: String, endIso: String, limit: Int): List<EventEntity>

    /** Same as [between] but only rows that carry a snapshot (gallery). */
    @Query(
        "SELECT * FROM events WHERE timestamp >= :startIso AND timestamp < :endIso " +
            "AND snapshot_name IS NOT NULL ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun betweenWithSnapshots(startIso: String, endIso: String, limit: Int): List<EventEntity>

    @Query("SELECT MIN(timestamp) FROM events")
    suspend fun oldestTimestamp(): String?

    @Query("SELECT snapshot_name, video_name FROM events WHERE timestamp < :olderThanIso")
    suspend fun mediaOlderThan(olderThanIso: String): List<MediaRef>

    @Query("DELETE FROM events WHERE timestamp < :olderThanIso")
    suspend fun deleteOlderThan(olderThanIso: String): Int

    @Query("SELECT snapshot_name, video_name FROM events")
    suspend fun allMedia(): List<MediaRef>

    @Query("SELECT id FROM events WHERE timestamp < :olderThanIso")
    suspend fun idsOlderThan(olderThanIso: String): List<Long>

    @Query("SELECT id FROM events")
    suspend fun allIds(): List<Long>

    @Query("DELETE FROM events")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    @Query("UPDATE events SET channel_statuses = :json WHERE id = :id")
    suspend fun updateChannelStatusesRaw(id: Long, json: String)

    /**
     * Read-modify-write of one channel's status as a single transaction;
     * concurrent flips for different channels no longer lose each other.
     */
    @Transaction
    suspend fun flipChannelStatusJson(eventId: Long, channelId: String, status: String) {
        val row = byId(eventId) ?: return
        val statuses = row.channelStatuses?.let(::decodeChannelStatusMap) ?: return
        if (statuses[channelId] == status) return
        updateChannelStatusesRaw(eventId, jsonEncodeChannelStatusMap(statuses + (channelId to status)))
    }

    /**
     * Collect-then-delete media refs atomically; concurrent inserts between
     * the select and the delete can no longer orphan files or double-delete.
     */
    @Transaction
    suspend fun deleteEventsCollectingRefs(olderThanIso: String?): List<MediaRef> {
        val refs = if (olderThanIso == null) allMedia() else mediaOlderThan(olderThanIso)
        if (olderThanIso == null) deleteAll() else deleteOlderThan(olderThanIso)
        return refs
    }

    data class MediaRef(val snapshot_name: String?, val video_name: String?)
}
