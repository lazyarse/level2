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
        val app = applicationContext
        val db = AppDatabase.get(app)
        val settings = SettingsStore(app, EncryptedSecretStore(app)).load()
        val snapshots = FileSnapshotStore(File(app.filesDir, "snapshots").absolutePath)
        val eventLog = RoomEventLog(db.eventDao())

        val drainer = OutboxDrainer(
            queue = OutboxStore.from(db),
            sendNotify = { row -> deliverNotify(row, settings.channelConfigs, snapshots) },
            sendBackup = { row -> uploadBackup(row, settings.cloudBackup) },
            onDelivered = { row -> flip(row, eventLog, EventPipeline.STATUS_DELIVERED) },
            onExpired = { row -> flip(row, eventLog, EventPipeline.STATUS_FAILED) },
        )
        return if (drainer.drainOnce()) Result.retry() else Result.success()
    }

    /**
     * Uploads one backup row. mediaPath is either a snapshot file name
     * (resolved under filesDir/snapshots) or MonitoringRuntime's
     * "clip:<displayName>" MediaStore reference.
     */
    private suspend fun uploadBackup(
        row: io.securitycam.level2.storage.OutboxEntity,
        cloud: io.securitycam.level2.core.CloudBackupSettings,
    ): Boolean {
        val uploader = CloudUploaderRegistry.forSettings(cloud) ?: return false
        val remoteKey = row.remotePath ?: return false
        val media = row.mediaPath ?: return false
        return if (media.startsWith(MonitoringRuntime.CLIP_MEDIA_PREFIX)) {
            val name = media.removePrefix(MonitoringRuntime.CLIP_MEDIA_PREFIX)
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            val ok = uploader.upload(remoteKey, "video/mp4", -1L) {
                runCatching { VideoClipRecorder.openStream(name) }.getOrNull().also {
                    if (it != null) opened.set(true)
                } ?: throw IllegalStateException("clip unavailable")
            }
            // Distinguish transport failure from missing media so missing
            // clips expire instead of retrying forever.
            ok || !opened.get()
        } else {
            val file = File(applicationContext.filesDir, "snapshots/$media")
            if (!file.exists()) return true // nothing left to back up; drop quietly
            uploader.upload(remoteKey, "image/jpeg", file.length()) { file.inputStream() }
        }
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
        val config = configs.firstOrNull { it.id == row.channelId } ?: return false
        val factory = ChannelRegistry.factories[config.type] ?: return false
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
        } catch (_: Exception) {
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
