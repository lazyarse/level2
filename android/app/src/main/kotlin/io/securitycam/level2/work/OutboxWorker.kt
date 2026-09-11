package io.securitycam.level2.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.securitycam.level2.backup.CloudUploaderRegistry
import io.securitycam.level2.camera_service.VideoClipRecorder
import io.securitycam.level2.channels.ChannelRegistry
import io.securitycam.level2.channels.OutboxDrainer
import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.event.EventPipeline
import io.securitycam.level2.monitor.MonitoringRuntime
import io.securitycam.level2.storage.AppDatabase
import io.securitycam.level2.storage.EncryptedSecretStore
import io.securitycam.level2.storage.FileSnapshotStore
import io.securitycam.level2.storage.OutboxEntity
import io.securitycam.level2.storage.OutboxKind
import io.securitycam.level2.storage.OutboxStore
import io.securitycam.level2.storage.RoomEventLog
import io.securitycam.level2.storage.SettingsStore
import java.io.File
import java.time.Instant

/**
 * Drains the offline outbox whenever connectivity returns (WorkManager
 * CONNECTED constraint). Notifications rebuild their [AlertMessage] from the
 * row (snapshot bytes reload from [FileSnapshotStore]) and go through the
 * same [ChannelRegistry] factories as live sends; success flips the stored
 * event's channel status to "delivered".
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        android.util.Log.i("OutboxWorker", "drain started")
        val app = applicationContext
        // Setup failures (corrupt DB, unreadable settings) are not
        // connectivity blips: fail instead of retrying forever.
        val db = try {
            AppDatabase.get(app)
        } catch (t: Throwable) {
            android.util.Log.w("OutboxWorker", "database unavailable", t)
            return Result.failure()
        }
        val settings = try {
            SettingsStore(app, EncryptedSecretStore(app)).load()
        } catch (t: Throwable) {
            android.util.Log.w("OutboxWorker", "settings load failed", t)
            return Result.failure()
        }
        val snapshots = FileSnapshotStore(File(app.filesDir, "snapshots").absolutePath)
        val eventLog = RoomEventLog(db.eventDao())

        val drainer = OutboxDrainer(
            queue = OutboxStore.from(db),
            sendNotify = { row -> deliverNotify(row, settings.channelConfigs, snapshots) },
            sendBackup = { row -> uploadBackup(row, settings.cloudBackup) },
            onDelivered = { row ->
                android.util.Log.i("OutboxWorker", "delivered id=${row.id} kind=${row.kind}")
                flip(row, eventLog, EventPipeline.STATUS_DELIVERED)
            },
            onExpired = { row ->
                android.util.Log.w(
                    "OutboxWorker",
                    "expired id=${row.id} kind=${row.kind} attempts=${row.attempts}",
                )
                flip(row, eventLog, EventPipeline.STATUS_FAILED)
            },
        )
        val more = try {
            drainer.drainOnce()
        } catch (t: Exception) {
            android.util.Log.w("OutboxWorker", "drain failed", t)
            return Result.retry()
        }
        android.util.Log.i("OutboxWorker", "drain finished more=$more")
        return if (more) Result.retry() else Result.success()
    }

    /**
     * Uploads one backup row. mediaPath is either a snapshot file name
     * (resolved under filesDir/snapshots) or MonitoringRuntime's
     * "clip:<displayName>" MediaStore reference.
     */
    private suspend fun uploadBackup(
        row: io.securitycam.level2.storage.OutboxEntity,
        cloud: io.securitycam.level2.core.CloudBackupSettings,
    ): Boolean = runCatching {
        val uploader = CloudUploaderRegistry.forSettings(cloud) ?: run {
            // No uploader for these settings (disabled/unknown backend):
            // expire rather than retry forever.
            android.util.Log.w("OutboxWorker", "no uploader for backup id=${row.id}; dropping")
            return@runCatching true
        }
        val remoteKey = row.remotePath ?: run {
            android.util.Log.w("OutboxWorker", "backup id=${row.id} missing remotePath; dropping")
            return@runCatching true
        }
        val media = row.mediaPath ?: run {
            android.util.Log.w("OutboxWorker", "backup id=${row.id} missing mediaPath; dropping")
            return@runCatching true
        }
        if (media.startsWith(MonitoringRuntime.CLIP_MEDIA_PREFIX)) {
            val name = media.removePrefix(MonitoringRuntime.CLIP_MEDIA_PREFIX)
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            // TOCTOU: the clip may be deleted between the existence check and
            // the open — a missing clip expires instead of retrying forever.
            val ok = uploader.upload(remoteKey, "video/mp4", -1L) {
                runCatching { VideoClipRecorder.openStream(name) }.getOrNull().also {
                    if (it != null) opened.set(true)
                } ?: throw IllegalStateException("clip unavailable")
            }
            if (!ok && !opened.get()) {
                android.util.Log.w("OutboxWorker", "clip $name gone; dropping backup id=${row.id}")
            } else if (!ok) {
                android.util.Log.w("OutboxWorker", "backup upload failed id=${row.id}")
            }
            // Distinguish transport failure from missing media so missing
            // clips expire instead of retrying forever.
            ok || !opened.get()
        } else {
            val file = File(applicationContext.filesDir, "snapshots/$media")
            if (!file.exists()) {
                android.util.Log.w("OutboxWorker", "snapshot $media gone; dropping backup id=${row.id}")
                return@runCatching true // nothing left to back up; drop quietly
            }
            val ok = uploader.upload(remoteKey, "image/jpeg", file.length()) { file.inputStream() }
            if (!ok) android.util.Log.w("OutboxWorker", "backup upload failed id=${row.id}")
            ok
        }
    }.getOrElse { t ->
        android.util.Log.w("OutboxWorker", "backup upload threw id=${row.id}", t)
        false
    }

    private suspend fun flip(row: OutboxEntity, log: RoomEventLog, status: String) {
        val eventId = row.eventId ?: return
        val channelId = row.channelId ?: return
        runCatching { log.flipChannelStatus(eventId, channelId, status) }
    }

    private suspend fun deliverNotify(
        row: OutboxEntity,
        configs: List<io.securitycam.level2.core.ChannelConfig>,
        snapshots: FileSnapshotStore,
    ): Boolean {
        val config = configs.firstOrNull { it.id == row.channelId } ?: run {
            // Unknown target (channel deleted): expire instead of retrying.
            android.util.Log.w("OutboxWorker", "unknown channel ${row.channelId}; dropping notify id=${row.id}")
            return true
        }
        if (!config.enabled && config.type != "log") {
            android.util.Log.w("OutboxWorker", "channel ${config.id} disabled; dropping notify id=${row.id}")
            return true
        }
        val factory = ChannelRegistry.factories[config.type] ?: run {
            android.util.Log.w("OutboxWorker", "no factory for type ${config.type}; dropping notify id=${row.id}")
            return true
        }
        val message = AlertMessage(
            timestamp = row.eventTime?.let(Instant::ofEpochMilli) ?: Instant.now(),
            triggerType = row.triggerType ?: TriggerType.merged,
            text = row.text.orEmpty(),
            snapshot = row.snapshotName?.let { name ->
                runCatching { snapshots.load(name) }.getOrNull()
            },
        )
        return try {
            factory(config).send(message)
            true
        } catch (e: Exception) {
            android.util.Log.w("OutboxWorker", "notify send failed id=${row.id}", e)
            false
        }
    }

    companion object {
        const val UNIQUE_NAME = "outbox-drain"

        /** Idempotent: KEEP policy means repeated calls never stack workers. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
