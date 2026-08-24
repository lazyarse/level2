package io.securitycam.level1.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.securitycam.level1.channels.ChannelRegistry
import io.securitycam.level1.channels.OutboxDrainer
import io.securitycam.level1.core.AlertMessage
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.event.EventPipeline
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.EncryptedSecretStore
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.OutboxEntity
import io.securitycam.level1.storage.OutboxKind
import io.securitycam.level1.storage.OutboxStore
import io.securitycam.level1.storage.RoomEventLog
import io.securitycam.level1.storage.SettingsStore
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
            // Backup rows arrive with the cloud-backup phase; treat as not-yet.
            sendBackup = { false },
            onDelivered = { row -> flip(row, eventLog, EventPipeline.STATUS_DELIVERED) },
            onExpired = { row -> flip(row, eventLog, EventPipeline.STATUS_FAILED) },
        )
        return if (drainer.drainOnce()) Result.retry() else Result.success()
    }

    private suspend fun flip(row: OutboxEntity, log: RoomEventLog, status: String) {
        val eventId = row.eventId ?: return
        val channelId = row.channelId ?: return
        runCatching { log.flipChannelStatus(eventId, channelId, status) }
    }

    private suspend fun deliverNotify(
        row: OutboxEntity,
        configs: List<io.securitycam.level1.core.ChannelConfig>,
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
