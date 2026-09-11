package io.securitycam.level2.storage

import android.content.Context
import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import io.securitycam.level2.event.DeletedMedia
import io.securitycam.level2.event.EventRecorder
import io.securitycam.level2.event.RecordedEvent
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room row for a recorded trigger event (schema v4 of the Dart event log). */
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
    fun recentFlow(limit: Int): Flow<List<EventEntity>>

    /** Emits the row count on every insert/delete; drives live UI refreshes. */
    @Query("SELECT COUNT(*) FROM events")
    fun countFlow(): Flow<Long>

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

@Database(entities = [EventEntity::class, OutboxEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v3 -> v4: adds the nullable trigger-detail column. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN detail TEXT")
            }
        }

        /** v4 -> v5: adds the offline-delivery outbox queue. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`channelId` TEXT, " +
                        "`eventId` INTEGER, " +
                        "`triggerType` TEXT, " +
                        "`eventTime` INTEGER, " +
                        "`text` TEXT, " +
                        "`snapshotName` TEXT, " +
                        "`mediaPath` TEXT, " +
                        "`remotePath` TEXT, " +
                        "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastAttemptAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_createdAt` ON `outbox` (`createdAt`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "events.db",
            ).addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                // Safety net only: every version this codebase ever shipped
                // (3→4→5) migrates explicitly above. This fires solely for a
                // foreign/corrupt db file, where starting fresh beats a
                // startup crash (event media on disk is unaffected).
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

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
    m.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"${k.replace("\"", "\\\"")}\":\"${v.replace("\"", "\\\"")}\""
    }

internal fun jsonEncodeChannelStatusList(l: List<String>): String =
    l.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }

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