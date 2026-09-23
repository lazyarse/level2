package io.securitycam.level2.backup

import io.securitycam.level2.core.CloudBackupSettings
import java.io.InputStream
import java.net.URI
import java.net.URL

/**
 * S3-compatible backend (AWS S3, Backblaze B2, Minio, Wasabi) via path-style
 * addressing and Signature V4 with UNSIGNED-PAYLOAD — no SDK dependency.
 */
class S3Uploader(private val settings: CloudBackupSettings) : HttpCloudUploader() {

    override val backendId: String get() = "s3"

    override val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    override val readTimeoutMs: Int = READ_TIMEOUT_MS
    override val chunkSize: Int = CHUNK_SIZE

    private fun urlFor(remoteKey: String): Pair<URL, String> {
        val endpoint = settings.serverUrl.trim().trimEnd('/')
        val bucket = settings.bucketOrPath.trim().trim('/')
        val key = remoteKey.trim('/')
        val path = "$endpoint/$bucket/$key"
        val url = URI(path).toURL()
        val canonicalUri = "/$bucket/$key"
        return url to canonicalUri
    }

    private fun hostOf(url: URL): String =
        url.host + if (url.port !in listOf(-1, 80, 443)) ":${url.port}" else ""

    private fun sign(
        method: String,
        url: URL,
        canonicalUri: String,
        contentType: String? = null,
    ): Map<String, String> {
        val signed = SigV4.sign(
            method = method,
            host = hostOf(url),
            canonicalUri = canonicalUri,
            canonicalQuery = "",
            region = settings.region.ifBlank { "us-east-1" },
            service = "s3",
            accessKeyId = settings.username,
            secretAccessKey = settings.password,
            extraHeaders = if (contentType != null) mapOf("content-type" to contentType) else emptyMap(),
            payloadHash = SigV4.UNSIGNED_PAYLOAD,
        )
        return mapOf(
            "Authorization" to signed.authorizationHeader,
            "x-amz-date" to signed.amzDate,
            "x-amz-content-sha256" to signed.payloadHash,
        )
    }

    override suspend fun validate(): Boolean = attempt {
        if (!CloudUploaderRegistry.plainHttpAllowed(settings.serverUrl)) return@attempt false
        val (url, canonicalUri) = urlFor("")
        openConnection(url, "HEAD", sign("HEAD", url, canonicalUri)).finishOk(404)
    }

    override suspend fun upload(
        remoteKey: String,
        contentType: String,
        size: Long,
        openInput: () -> InputStream,
    ): Boolean = attempt {
        if (!CloudUploaderRegistry.plainHttpAllowed(settings.serverUrl)) return@attempt false
        val (url, canonicalUri) = urlFor(remoteKey)
        val conn = openConnection(url, "PUT", sign("PUT", url, canonicalUri, contentType))
        try {
            conn.putBytes(contentType, size, openInput)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val CHUNK_SIZE = 256 * 1024
    }
}
