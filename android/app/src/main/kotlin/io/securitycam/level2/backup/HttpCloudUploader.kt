package io.securitycam.level2.backup

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared HttpURLConnection transport for the cloud backends: timeouts,
 * header setup, success checks with disconnect, upload streaming, and the
 * never-throw contract ([CloudUploader.upload] returns false instead).
 * Auth and URL layout stay in the subclasses.
 */
abstract class HttpCloudUploader : CloudUploader {

    protected abstract val connectTimeoutMs: Int
    protected abstract val readTimeoutMs: Int
    protected abstract val chunkSize: Int

    protected fun openConnection(
        url: URL,
        method: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        for ((name, value) in headers) {
            conn.setRequestProperty(name, value)
        }
        return conn
    }

    /** True on 2xx (plus [extraOk]), always disconnecting. */
    protected fun HttpURLConnection.finishOk(vararg extraOk: Int): Boolean =
        try {
            responseCode in 200..299 || responseCode in extraOk
        } finally {
            disconnect()
        }

    /** Streams [openInput] as the request body; true on 2xx. */
    protected fun HttpURLConnection.putBytes(
        contentType: String,
        size: Long,
        openInput: () -> InputStream,
    ): Boolean {
        doOutput = true
        setRequestProperty("Content-Type", contentType)
        if (size >= 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(chunkSize)
        openInput().use { input ->
            outputStream.use { output -> input.copyTo(output) }
        }
        return responseCode in 200..299
    }

    /** Runs [block] on IO, converting any transport failure to false. */
    protected suspend fun attempt(
        block: suspend () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (_: Exception) {
            false
        }
    }
}
