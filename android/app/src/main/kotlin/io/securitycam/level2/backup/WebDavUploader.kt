package io.securitycam.level2.backup

import android.util.Base64
import io.securitycam.level2.core.CloudBackupSettings
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV backend: PUT for uploads, PROPFIND (Depth: 0) as the connection
 * probe. Basic auth over TLS; plain HTTP is refused unless the host is a
 * private/LAN address.
 */
class WebDavUploader(private val settings: CloudBackupSettings) : CloudUploader {

    override val backendId: String get() = "webdav"

    private fun urlFor(remoteKey: String): URL {
        val base = settings.serverUrl.trim().trimEnd('/')
        val dir = settings.bucketOrPath.trim().trim('/')
        val key = remoteKey.trim('/')
        val path = listOf(base, dir, key).filter { it.isNotEmpty() }.joinToString("/")
        return URI(path).toURL()
    }

    private fun basicAuth(): String =
        Base64.encodeToString(
            "${settings.username}:${settings.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

    private fun openConnection(url: URL, method: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Basic ${basicAuth()}")
        }
        return conn
    }

    override suspend fun validate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = urlFor(settings.bucketOrPath.ifBlank { "" })
            if (!CloudUploaderRegistry.plainHttpAllowed(url.toString())) return@withContext false
            val conn = openConnection(url, "PROPFIND")
            conn.setRequestProperty("Depth", "0")
            try {
                conn.responseCode in 200..299 || conn.responseCode == 404
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun upload(
        remoteKey: String,
        contentType: String,
        size: Long,
        openInput: () -> InputStream,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = urlFor(remoteKey)
            if (!CloudUploaderRegistry.plainHttpAllowed(url.toString())) return@withContext false
            ensureCollection(url)
            val conn = openConnection(url, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (size >= 0) conn.setFixedLengthStreamingMode(size)
            else conn.setChunkedStreamingMode(CHUNK_SIZE)
            try {
                openInput().use { input ->
                    conn.outputStream.use { output -> input.copyTo(output) }
                }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Best-effort MKCOL for the parent collection; 405 (= exists) is fine. */
    private fun ensureCollection(fileUrl: URL) {
        try {
            val dirPath = fileUrl.path.substringBeforeLast('/', missingDelimiterValue = "/")
            val dir = URI(
                fileUrl.protocol, fileUrl.userInfo, fileUrl.host, fileUrl.port,
                dirPath, null, null,
            ).toURL()
            val conn = openConnection(dir, "MKCOL")
            try { conn.responseCode } finally { conn.disconnect() }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CHUNK_SIZE = 64 * 1024
    }
}
