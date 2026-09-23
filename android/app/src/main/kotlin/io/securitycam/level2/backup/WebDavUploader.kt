package io.securitycam.level2.backup

import android.util.Base64
import io.securitycam.level2.core.CloudBackupSettings
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * WebDAV backend: PUT for uploads, PROPFIND (Depth: 0) as the connection
 * probe. Basic auth over TLS; plain HTTP is refused unless the host is a
 * private/LAN address.
 */
class WebDavUploader(private val settings: CloudBackupSettings) : HttpCloudUploader() {

    override val backendId: String get() = "webdav"

    override val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    override val readTimeoutMs: Int = READ_TIMEOUT_MS
    override val chunkSize: Int = CHUNK_SIZE

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

    private fun openAuthenticated(url: URL, method: String): HttpURLConnection =
        openConnection(
            url,
            method,
            headers = if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
                mapOf("Authorization" to "Basic ${basicAuth()}")
            } else {
                emptyMap()
            },
        )

    override suspend fun validate(): Boolean = attempt {
        val url = urlFor(settings.bucketOrPath.ifBlank { "" })
        if (!CloudUploaderRegistry.plainHttpAllowed(url.toString())) return@attempt false
        val conn = openAuthenticated(url, "PROPFIND")
        conn.setRequestProperty("Depth", "0")
        conn.finishOk(404)
    }

    override suspend fun upload(
        remoteKey: String,
        contentType: String,
        size: Long,
        openInput: () -> InputStream,
    ): Boolean = attempt {
        val url = urlFor(remoteKey)
        if (!CloudUploaderRegistry.plainHttpAllowed(url.toString())) return@attempt false
        ensureCollection(url)
        val conn = openAuthenticated(url, "PUT")
        try {
            conn.putBytes(contentType, size, openInput)
        } finally {
            conn.disconnect()
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
            openAuthenticated(dir, "MKCOL").finishOk()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CHUNK_SIZE = 64 * 1024
    }
}
