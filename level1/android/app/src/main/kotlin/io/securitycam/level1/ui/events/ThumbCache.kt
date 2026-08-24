package io.securitycam.level1.ui.events

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide memory cache for decoded thumbnails (events snapshots and
 * enrolled-face photos), keyed by caller-chosen strings.
 *
 * - Byte-counted LRU (~1/8 of max heap, clamped to [4 MB, 32 MB]) bounds
 *   memory while letting rapid scrolling reuse already-decoded bitmaps.
 * - Loads run off the main thread and pass through EXIF-aware decoding
 *   ([decodeUpright]); per-key mutexes collapse concurrent duplicate loads
 *   during fast flings into a single disk-read+decode.
 * - Null results (missing files) are not cached so transient failures retry.
 */
object ThumbCache {

    /** Longest-side cap for list thumbnails cached via [getOrLoad]. */
    const val THUMB_MAX_DIM = 256

    @Volatile
    private var lru: LruCache<String, Bitmap> = newLru(defaultMaxKb())

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun defaultMaxKb(): Int {
        val eighth = (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()
        return eighth.coerceIn(4 * 1024, 32 * 1024)
    }

    private fun newLru(maxKb: Int): LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                ((value.byteCount / 1024).toInt().coerceAtLeast(1))
        }

    /** Synchronous cache probe: lets composables render hits in first frame. */
    fun peek(key: String): Bitmap? = runCatching { lru.get(key) }.getOrNull()

    /**
     * Returns the cached bitmap for [key], loading+decoding it once on miss.
     * [bytesLoader] runs on IO; decode on Default; both skipped on hit.
     * [maxDim] downsamples the decode (see [decodeUpright]).
     */
    suspend fun getOrLoad(
        key: String,
        maxDim: Int? = null,
        bytesLoader: suspend () -> ByteArray?,
    ): Bitmap? {
        lru.get(key)?.let { return it }
        val mutex = inFlight.computeIfAbsent(key) { Mutex() }
        return try {
            mutex.withLock {
                lru.get(key)?.let { return it }
                val bytes = withContext(Dispatchers.IO) { bytesLoader() }
                    ?: return null
                val bmp = withContext(Dispatchers.Default) { decodeUpright(bytes, maxDim) }
                    ?: return null
                lru.put(key, bmp)
                bmp
            }
        } finally {
            inFlight.remove(key)
        }
    }

    /** Drops one entry (e.g. when the underlying media file is deleted). */
    fun evict(key: String) {
        runCatching { lru.remove(key) }
    }

    fun clear() {
        lru.evictAll()
    }

    /** Test seam: rebuild the cache with an explicit budget. */
    internal fun resetForTest(maxKb: Int) {
        lru = newLru(maxKb.coerceAtLeast(1))
    }
}
