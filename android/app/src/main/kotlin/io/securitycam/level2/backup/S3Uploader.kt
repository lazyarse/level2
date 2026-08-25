package io.securitycam.level2.backup

import io.securitycam.level2.core.CloudBackupSettings
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S3-compatible backend (AWS S3, Backblaze B2, Minio, Wasabi) via path-style
 * addressing and Signature V4 with UNSIGNED-PAYLOAD — no SDK dependency.
 */
class S3Uploader(private val settings: CloudBackupSettings) : CloudUploader {

    override val backendId: String get() = "s3"

    private fun urlFor(remoteKey: String): Pair<URL, String> {
        val endpoint = settings.serverUrl.trim().trimEnd('/')
        val bucket = settings.bucketOrPath.trim().trim('/')
        val key = remoteKey.trim('/')
        val path = "$endpoint/$bucket/$key"
        val url = URI(path).toURL()
        val canonicalUri = "/$bucket/$key"
        return url to canonicalUri
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        return conn
    }

    override suspend fun validate(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!CloudUploaderRegistry.plainHttpAllowed(settings.serverUrl)) return@withContext false
            val (url, canonicalUri) = urlFor("")
            val host = url.host + if (url.port !in listOf(-1, 80, 443)) ":${url.port}" else ""
            val signed = SigV4.sign(
                method = "HEAD",
                host = host,
                canonicalUri = canonicalUri,
                canonicalQuery = "",
                region = settings.region.ifBlank { "us-east-1" },
                service = "s3",
                accessKeyId = settings.username,
                secretAccessKey = settings.password,
                payloadHash = SigV4.UNSIGNED_PAYLOAD,
            )
            val conn = openConnection(url).apply {
                requestMethod = "HEAD"
                setRequestProperty("Authorization", signed.authorizationHeader)
                setRequestProperty("x-amz-date", signed.amzDate)
                setRequestProperty("x-amz-content-sha256", signed.payloadHash)
            }
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
            if (!CloudUploaderRegistry.plainHttpAllowed(settings.serverUrl)) return@withContext false
            val (url, canonicalUri) = urlFor(remoteKey)
            val host = url.host + if (url.port !in listOf(-1, 80, 443)) ":${url.port}" else ""
            val signed = SigV4.sign(
                method = "PUT",
                host = host,
                canonicalUri = canonicalUri,
                canonicalQuery = "",
                region = settings.region.ifBlank { "us-east-1" },
                service = "s3",
                accessKeyId = settings.username,
                secretAccessKey = settings.password,
                extraHeaders = mapOf("content-type" to contentType),
                payloadHash = SigV4.UNSIGNED_PAYLOAD,
            )
            val conn = openConnection(url).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Authorization", signed.authorizationHeader)
                setRequestProperty("x-amz-date", signed.amzDate)
                setRequestProperty("x-amz-content-sha256", signed.payloadHash)
                setRequestProperty("Content-Type", contentType)
                if (size >= 0) setFixedLengthStreamingMode(size)
                else setChunkedStreamingMode(CHUNK_SIZE)
            }
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val CHUNK_SIZE = 256 * 1024
    }
}
