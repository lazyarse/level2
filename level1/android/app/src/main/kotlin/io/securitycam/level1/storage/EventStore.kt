package io.securitycam.level1.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.securitycam.level1.event.DeletedMedia
import io.securitycam.level1.event.EventRecorder
import io.securitycam.level1.event.RecordedEvent
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room row for a recorded trigger event (schema v3 of the Dart event log). */
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
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<EventEntity>>

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

    @Query("SELECT snapshot_name, video_name FROM events WHERE timestamp < :olderThanIso")
    suspend fun mediaOlderThan(olderThanIso: String): List<MediaRef>

    @Query("DELETE FROM events WHERE timestamp < :olderThanIso")
    suspend fun deleteOlderThan(olderThanIso: String): Int

    @Query("SELECT snapshot_name, video_name FROM events")
    suspend fun allMedia(): List<MediaRef>

    @Query("DELETE FROM events")
    suspend fun deleteAll(): Int

    data class MediaRef(val snapshot_name: String?, val video_name: String?)
}

@Database(entities = [EventEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "events.db",
            ).build().also { instance = it }
        }
    }
}

/**
 * Room-backed [EventRecorder] (port of `lib/storage/event_log.dart`,
 * schema version 3).
 */
class RoomEventLog(private val dao: EventDao) : EventRecorder {

    override suspend fun record(event: RecordedEvent) {
        dao.insert(
            EventEntity(
                timestamp = event.timestamp.toString(),
                cameraName = event.cameraName,
                triggerType = event.triggerType,
                score = event.score,
                snapshotName = event.snapshotName,
                videoName = event.videoName,
                channelStatuses = jsonEncodeStringMap(event.channelStatuses),
                triggerTypes = if (event.triggerTypes.isEmpty()) null else jsonEncodeStringList(event.triggerTypes),
            ),
        )
    }

    override suspend fun deleteEvents(olderThan: Instant?): DeletedMedia {
        val refs: List<EventDao.MediaRef> = if (olderThan == null) {
            dao.allMedia()
        } else {
            dao.mediaOlderThan(olderThan.toString())
        }
        val deleted = DeletedMedia(
            snapshotNames = refs.mapNotNull { it.snapshot_name },
            videoNames = refs.mapNotNull { it.video_name },
        )
        if (olderThan == null) dao.deleteAll() else dao.deleteOlderThan(olderThan.toString())
        return deleted
    }

    /** Most recent rows, newest first. */
    suspend fun recent(limit: Int = 100): List<RecordedEventRow> =
        dao.recent(limit).map { it.toRow() }

    fun recentFlow(limit: Int = 100): Flow<List<RecordedEventRow>> =
        dao.recentFlow(limit).map { rows -> rows.map { it.toRow() } }

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
        return rows.map { it.toRow() }
    }

    private fun EventEntity.toRow(): RecordedEventRow = RecordedEventRow(
        id = id,
        timestamp = Instant.parse(timestamp),
        cameraName = cameraName,
        triggerType = triggerType,
        score = score,
        snapshotName = snapshotName,
        videoName = videoName,
        channelStatuses = channelStatuses?.let(::decodeStringMap) ?: emptyMap(),
        triggerTypes = triggerTypes?.let(::decodeStringList) ?: emptyList(),
    )

    companion object {
        private fun jsonEncodeStringMap(m: Map<String, String>): String =
            m.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"${k.replace("\"", "\\\"")}\":\"${v.replace("\"", "\\\"")}\""
            }

        private fun jsonEncodeStringList(l: List<String>): String =
            l.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }

        private fun decodeStringMap(raw: String): Map<String, String> = try {
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

        private fun decodeStringList(raw: String): List<String> = try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
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
)