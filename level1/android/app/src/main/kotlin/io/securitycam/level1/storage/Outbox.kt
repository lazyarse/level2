package io.securitycam.level1.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * A queued delivery awaiting connectivity (schema v5). One row per
 * (event × channel) for notifications, one per media item for cloud backups —
 * see docs/plans/2026-08-24-offline-alert-outbox-design.md.
 */
@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch ms of enqueue time; FIFO drain order. */
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    /** "notify" | "backup" (see [OutboxKind]). */
    @ColumnInfo(name = "kind") val kind: String,
    // ---- notify rows ----
    @ColumnInfo(name = "channelId") val channelId: String? = null,
    /** Events-table row this delivery belongs to; enables the late status flip. */
    @ColumnInfo(name = "eventId") val eventId: Long? = null,
    @ColumnInfo(name = "triggerType") val triggerType: String? = null,
    @ColumnInfo(name = "eventTime") val eventTime: Long? = null,
    /** Pre-rendered alert text (camera name + time already included). */
    @ColumnInfo(name = "text") val text: String? = null,
    /** SnapshotStore reference; bytes reload at send time. */
    @ColumnInfo(name = "snapshotName") val snapshotName: String? = null,
    // ---- backup rows (cloud-backup phase) ----
    @ColumnInfo(name = "mediaPath") val mediaPath: String? = null,
    @ColumnInfo(name = "remotePath") val remotePath: String? = null,
    // ---- retry bookkeeping ----
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "lastAttemptAt") val lastAttemptAt: Long? = null,
)

/** Outbox row kinds. */
object OutboxKind {
    const val NOTIFY = "notify"
    const val BACKUP = "backup"
}

/** Shared retry/expiry policy for every outbox row. */
object OutboxPolicy {
    const val MAX_ATTEMPTS = 5
    val MAX_AGE: Duration = Duration.ofHours(24)

    fun isExpired(row: OutboxEntity, nowMs: Long): Boolean =
        row.attempts >= MAX_ATTEMPTS || nowMs - row.createdAt > MAX_AGE.toMillis()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun peekBatch(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun countFlow(): Flow<Long>

    @Query(
        "UPDATE outbox SET attempts = :attempts, lastAttemptAt = :lastAttemptAt WHERE id = :id",
    )
    suspend fun markAttempted(id: Long, attempts: Int, lastAttemptAt: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "DELETE FROM outbox WHERE attempts >= :maxAttempts OR (:nowMs - createdAt) > :maxAgeMs",
    )
    suspend fun dropExpired(maxAttempts: Int, nowMs: Long, maxAgeMs: Long): Int
}

/** Minimal queue contract so drain logic is testable without Room. */
interface OutboxQueue {
    suspend fun peekBatch(limit: Int = 20): List<OutboxEntity>
    suspend fun markAttempted(id: Long, attempts: Int, lastAttemptAt: Long)
    suspend fun delete(id: Long)
}

/** Thin facade over [OutboxDao] so callers never touch Room directly. */
class OutboxStore(private val dao: OutboxDao) : OutboxQueue {
    override suspend fun peekBatch(limit: Int): List<OutboxEntity> =
        dao.peekBatch(limit)

    override suspend fun markAttempted(id: Long, attempts: Int, lastAttemptAt: Long) =
        dao.markAttempted(id, attempts, lastAttemptAt)

    override suspend fun delete(id: Long) = dao.delete(id)

    suspend fun enqueue(row: OutboxEntity) {
        dao.insert(row)
    }

    fun pendingCountFlow(): Flow<Long> = dao.countFlow()

    suspend fun dropExpired(nowMs: Long): Int =
        dao.dropExpired(OutboxPolicy.MAX_ATTEMPTS, nowMs, OutboxPolicy.MAX_AGE.toMillis())

    companion object {
        const val BATCH_SIZE = 20

        fun from(db: AppDatabase): OutboxStore = OutboxStore(db.outboxDao())
    }
}
