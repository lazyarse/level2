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
 * Fits [snapshot] under [maxBytes] for upload: already-small snapshots pass
 * through untouched (original mime kept); oversized ones are downscaled and
 * re-encoded as JPEG; corrupt/unsalvageable bytes yield null (caller falls
 * back to the text form). Bitmap work stays here so JVM unit tests avoid it
 * via injection (mirrors the email test-snapshot seam).
 */
internal fun fitSnapshotForUpload(
    snapshot: Snapshot,
    maxBytes: Int,
    maxSide: Int = 1280,
): Snapshot? {
    if (snapshot.bytes.size <= maxBytes) return snapshot
    val src = android.graphics.BitmapFactory.decodeByteArray(
        snapshot.bytes, 0, snapshot.bytes.size,
    ) ?: return null
    try {
        val scale = (maxSide / maxOf(src.width, src.height).toFloat()).coerceAtMost(1f)
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        try {
            for (quality in listOf(85, 70, 55, 40)) {
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.isNotEmpty() && bytes.size <= maxBytes) {
                    val stem = snapshot.name.substringBeforeLast(".")
                    return Snapshot(bytes, "image/jpeg", "$stem.jpg")
                }
            }
            return null
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    } finally {
        src.recycle()
    }
}

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
