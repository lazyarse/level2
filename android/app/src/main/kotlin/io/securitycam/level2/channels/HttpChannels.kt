package io.securitycam.level2.channels

import io.securitycam.level2.core.Snapshot
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

/** Shared HTTP plumbing for the API channels (Telegram/webhook/Pushover). */
internal fun newHttpClient(): OkHttpClient =
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

/**
 * Parses [mime], falling back to `image/jpeg` for malformed types instead of
 * throwing (snapshot mime strings originate on-device and must never fail a send).
 */
internal fun safeMediaType(mime: String): MediaType =
    runCatching { mime.toMediaType() }.getOrElse { "image/jpeg".toMediaType() }

/**
 * Writes [snapshot] to a temp file off-main, runs [block], deletes the file.
 * Replaces the copy-pasted create/write/try/finally-delete in each channel.
 */
internal suspend fun <T> withTempSnapshot(snapshot: Snapshot, block: suspend (File) -> T): T {
    val tmp = withContext(Dispatchers.IO) {
        File.createTempFile("level2", ".img").also { it.writeBytes(snapshot.bytes) }
    }
    try {
        return block(tmp)
    } finally {
        withContext(Dispatchers.IO) { runCatching { tmp.delete() } }
    }
}
