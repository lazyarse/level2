package io.securitycam.level1.backup

import io.securitycam.level1.core.CloudBackupSettings
import java.io.InputStream

/**
 * Storage-backend contract for cloud clip/snapshot uploads (see
 * docs/plans/2026-08-24-cloud-backup-design.md). Implementations are pure JVM
 * (HttpURLConnection) so they unit-test against MockWebServer.
 */
interface CloudUploader {
    val backendId: String

    /** Cheap credential/reachability probe backing the "Test connection" UI. */
    suspend fun validate(): Boolean

    /**
     * Uploads [openInput] (size [size] bytes when known, -1 otherwise) to
     * [remoteKey]. Returns true on 2xx. Never throws for transport failures.
     */
    suspend fun upload(
        remoteKey: String,
        contentType: String,
        size: Long,
        openInput: () -> InputStream,
    ): Boolean
}

/** Builds `<camera>/<yyyy-MM-dd>/<fileName>` so any file browser stays sane. */
object RemoteKeys {
    fun forMedia(cameraName: String, fileName: String, atEpochMs: Long): String {
        val safeCamera = cameraName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            .ifEmpty { "camera" }
        val day = java.time.Instant.ofEpochMilli(atEpochMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        return "$safeCamera/$day/$fileName"
    }
}

/** Builds the configured backend implementation, or null when unusable. */
object CloudUploaderRegistry {
    /** Hosts allowed to speak plain HTTP (private addresses only). */
    private val LAN_HOST = Regex(
        "^(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*",
    )

    fun plainHttpAllowed(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        if (u.scheme == null) return@runCatching false
        if (u.scheme.equals("https", ignoreCase = true)) return@runCatching true
        u.scheme.equals("http", ignoreCase = true) && LAN_HOST.matches(u.host ?: "")
    }.getOrDefault(false)

    fun forSettings(settings: CloudBackupSettings): CloudUploader? {
        if (!settings.enabled) return null
        if (settings.serverUrl.isBlank()) return null
        return when (settings.backend) {
            "webdav" -> WebDavUploader(settings)
            "s3" -> S3Uploader(settings)
            else -> null
        }
    }
}
