package io.securitycam.level2.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import io.securitycam.level2.core.GifPreview
import io.securitycam.level2.core.Snapshot
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Keyframe-based GIF89a encoder (pure Kotlin, no Android classes).
 *
 * Feeds [encode] with per-frame ARGB pixel buffers and gets back a complete,
 * spec-compliant GIF: global colour table (median-cut quantised to a power of
 * two 2..256), NETSCAPE2.0 infinite loop, one Graphic-Control+Image block per
 * frame with the given delay in centiseconds, LZW-compressed index streams.
 *
 * Android ships no GIF *encoder*, so this is the self-contained ~240-line one.
 * Encoder properties (delay precision) are exercised by unit tests.
 */
object GifEncoder {
    /** One animation frame. [argb] is `width*height` packed `0xAARRGGBB` ints. */
    data class Frame(
        val delayCs: Int,
        val width: Int,
        val height: Int,
        val argb: IntArray,
    )

    private const val MAX_COLORS = 256
    private const val LZW_MAX_CODE = 4096
    private const val MAX_BITS = 12
    /**
     * Palette input bounds: stride-sample at most this many pixels across all
     * frames, then cap distinct colours before median-cut (which re-sorts per
     * split and stalls for a minute on 60 full frames of a low-end device).
     */
    private const val PALETTE_SAMPLE_PIXELS = 65_536
    private const val MAX_HISTOGRAM_ENTRIES = 4_096

    suspend fun encode(frames: List<Frame>): ByteArray {
        require(frames.isNotEmpty()) { "no frames" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }

        yield()
        val palette = globalPalette(frames)
        val bits = coerceToGifBits(palette.size)
        val paletteIndex = nearestIndexTable(palette)
        // Pad the applied palette to the power-of-two count the depth implies
        // (median-cut yields <= 256; the GCT must be 2, 4, 8, ... 256 entries).
        val tableSize = 1 shl bits
        val table = ByteArray(tableSize * 3)
        for (i in palette.indices) {
            val c = palette[i]
            table[i * 3] = (c ushr 16).toByte()
            table[i * 3 + 1] = (c ushr 8).toByte()
            table[i * 3 + 2] = c.toByte()
        }
        if (palette.isNotEmpty()) {
            val lastCol = palette.last()
            for (i in palette.size until tableSize) {
                table[i * 3] = (lastCol ushr 16).toByte()
                table[i * 3 + 1] = (lastCol ushr 8).toByte()
                table[i * 3 + 2] = lastCol.toByte()
            }
        }

        val out = ByteArrayOutputStream()
        // Header + logical screen descriptor.
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLe16(out, width)
        writeLe16(out, height)
        out.write(0x80 or ((bits - 1) shl 4) or (bits - 1)) // GCT flag + depth + size
        out.write(0)                          // background index
        out.write(0)                          // pixel aspect ratio
        out.write(table)

        // Netscape loop extension: loop forever (as animated previews do).
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        out.write(0)
        out.write(0)
        out.write(0)

        for (frame in frames) {
            // Graphic control extension: no transparency, keep frame, delay.
            out.write(0x21)
            out.write(0xF9)
            out.write(4)
            out.write(0x04) // disposal: do not dispose
            writeLe16(out, frame.delayCs.coerceIn(0, 0xFFFF))
            out.write(0)
            out.write(0)
            // Image descriptor (no local colour table, not interlaced).
            out.write(0x2C)
            writeLe16(out, 0)
            writeLe16(out, 0)
            writeLe16(out, frame.width)
            writeLe16(out, frame.height)
            out.write(0)
            // LZW minimum code size + compressed index stream.
            out.write(bits)
            writeSubBlocks(out, lzw(indexStream(frame.argb, paletteIndex), bits))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /**
     * Median-cut box. Entries are Longs packed `(rgb << 24) | count`, so each
     * box sorts/re-splits on a single array without boxing pairs.
     */
    private class RgbBox(private var data: LongArray) {
        var bounds: IntArray = IntArray(6)
        val weight: Int get() = data.size

        fun countAt(i: Int) = (data[i] and 0xFFFFFF).toInt()
        fun rAt(i: Int) = ((data[i] ushr 40) and 0xFF).toInt()
        fun gAt(i: Int) = ((data[i] ushr 32) and 0xFF).toInt()
        fun bAt(i: Int) = ((data[i] ushr 24) and 0xFF).toInt()
        fun sortByRed() { data = data.sortedWith(compareBy { it ushr 40 }).toLongArray() }
        fun sortByGreen() { data = data.sortedWith(compareBy { it ushr 32 }).toLongArray() }
        fun sortByBlue() { data = data.sortedWith(compareBy { it ushr 24 }).toLongArray() }
        fun copyOfRange(from: Int, to: Int) = RgbBox(data.copyOfRange(from, to))
    }

    internal suspend fun globalPalette(frames: List<Frame>): IntArray {
        // Stride-sample pixels so the histogram covers at most
        // PALETTE_SAMPLE_PIXELS samples no matter how many frames feed it;
        // per-frame start offsets spread coverage across the clip.
        val totalPixels = frames.sumOf { it.argb.size }
        val stride = maxOf(1, (totalPixels + PALETTE_SAMPLE_PIXELS - 1) / PALETTE_SAMPLE_PIXELS)
        val histogram = HashMap<Int, Int>()
        var sampled = 0
        for ((fi, frame) in frames.withIndex()) {
            val argb = frame.argb
            var i = fi % stride
            while (i < argb.size) {
                histogram.merge(argb[i] and 0x00FFFFFF, 1, Int::plus)
                i += stride
                if (++sampled % 4096 == 0) yield()
            }
            yield()
        }
        if (histogram.isEmpty()) return IntArray(0)
        if (histogram.size <= MAX_COLORS) {
            return histogram.keys.toIntArray().sortedArrayDescending()
        }
        // Cap distinct entries before median-cut with a single sort: the cut
        // re-sorts per split, so feeding it the whole distinct set is what
        // stalls 60-frame encodes on slow devices. Counts fit in 24 bits
        // because sampling bounds the total sample count.
        val sorted = histogram.entries.sortedByDescending { it.value }
        val kept = minOf(sorted.size, MAX_HISTOGRAM_ENTRIES)
        val entries = LongArray(kept)
        for (n in 0 until kept) {
            val e = sorted[n]
            entries[n] = (e.key.toLong() shl 24) or e.value.toLong()
        }

        fun minMax(box: RgbBox) {
            var rl = 0xFF; var rh = 0
            var gl = 0xFF; var gh = 0
            var bl = 0xFF; var bh = 0
            for (i in 0 until box.weight) {
                val r = box.rAt(i); val g = box.gAt(i); val b = box.bAt(i)
                if (r < rl) rl = r; if (r > rh) rh = r
                if (g < gl) gl = g; if (g > gh) gh = g
                if (b < bl) bl = b; if (b > bh) bh = b
            }
            box.bounds = intArrayOf(rl, rh, gl, gh, bl, bh)
        }

        fun rangeOf(box: RgbBox): Int {
            val b = box.bounds
            return maxOf(b[1] - b[0], b[3] - b[2], b[5] - b[4])
        }

        val root = RgbBox(entries)
        minMax(root)
        val boxes = ArrayList<RgbBox>()
        boxes.add(root)
        // Boxes with no valid split are terminal: skipping them (instead of
        // stopping the whole cut) keeps the palette rich — an early break
        // here froze real previews at ~16 colours — while still guaranteeing
        // termination, since every iteration either splits a box or retires
        // one and the box count is capped at MAX_COLORS.
        val terminal = HashSet<RgbBox>()
        while (boxes.size < MAX_COLORS) {
            yield()
            var widest = -1
            var widestRange = -1
            for (i in boxes.indices) {
                if (boxes[i] in terminal) continue
                val range = rangeOf(boxes[i])
                if (range > widestRange) {
                    widest = i
                    widestRange = range
                }
            }
            if (widest < 0 || widestRange <= 0) break
            // Median-cut split at the midpoint of the longest-range channel.
            val box = boxes[widest]
            val b = box.bounds
            val axisRed = b[1] - b[0] >= b[3] - b[2] && b[1] - b[0] >= b[5] - b[4]
            val axisGreen = !axisRed && b[3] - b[2] >= b[5] - b[4]
            when {
                axisRed -> box.sortByRed()
                axisGreen -> box.sortByGreen()
                else -> box.sortByBlue()
            }
            var total = 0
            for (i in 0 until box.weight) total += box.countAt(i)
            var half = 0
            var cut = 0
            for (i in 0 until box.weight) {
                half += box.countAt(i)
                if (half * 2 >= total) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= 0 || cut >= box.weight) {
                // No valid split for this box: the weighted median is its
                // last element, so re-looping on it would spin forever
                // (observed as encode hanging until withTimeout on-device).
                // Retire just this box and keep cutting the rest.
                terminal.add(box)
                continue
            }
            val left = box.copyOfRange(0, cut)
            val right = box.copyOfRange(cut, box.weight)
            minMax(left)
            minMax(right)
            boxes[widest] = left
            boxes.add(right)
        }
        val palette = IntArray(boxes.size)
        for (i in boxes.indices) {
            val box = boxes[i]
            var rs = 0L; var gs = 0L; var bs = 0L
            var wsum = 0L
            for (j in 0 until box.weight) {
                val w = box.countAt(j).toLong()
                rs += box.rAt(j).toLong() * w
                gs += box.gAt(j).toLong() * w
                bs += box.bAt(j).toLong() * w
                wsum += w
            }
            if (wsum == 0L) wsum = 1L
            val r = (rs / wsum).toInt().coerceIn(0, 0xFF)
            val g = (gs / wsum).toInt().coerceIn(0, 0xFF)
            val b = (bs / wsum).toInt().coerceIn(0, 0xFF)
            palette[i] = (r shl 16) or (g shl 8) or b
        }
        return palette
    }

    /** Round up to a GIF palette depth in 2..8 bits (min 2 per the spec). */
    internal fun bitsForColorCount(count: Int): Int {
        var bits = 1
        while ((1 shl bits) < count && bits < 8) bits++
        return if (bits == 1) 2 else bits
    }

    private fun coerceToGifBits(count: Int): Int = bitsForColorCount(maxOf(2, count))

    /**
     * Fast nearest-palette mapping via a 15-bit (5-5-5) colour-quantisation
     * lookup table, so per-pixel work is O(1) regardless of palette size.
     */
    internal fun nearestIndexTable(palette: IntArray): IntArray {
        val table = IntArray(1 shl 15)
        for (q in table.indices) {
            val rr = (q ushr 10) and 0x1F
            val gg = (q ushr 5) and 0x1F
            val bb = q and 0x1F
            // Mid-bin expansion: 5-bit index q represents [q*8, q*8+7) → q*8+4.
            val r = rr * 8 + 4
            val g = gg * 8 + 4
            val b = bb * 8 + 4
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val pr = (palette[i] ushr 16) and 0xFF
                val pg = (palette[i] ushr 8) and 0xFF
                val pb = palette[i] and 0xFF
                val dr = pr - r
                val dg = pg - g
                val db = pb - b
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            table[q] = best
        }
        return table
    }

    private fun indexStream(argb: IntArray, table: IntArray): ByteArray {
        val out = ByteArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val q = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
            out[i] = table[q].toByte()
        }
        return out
    }

    /** GIF-variant LZW (variable 2..12-bit codes, LSB-first packing). */
    internal suspend fun lzw(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeWidth = minCodeSize + 1
        var maxCode1 = 1 shl codeWidth
        var nextFree = eoi + 1
        val dict = HashMap<Int, Int>()

        fun resetDictionary() {
            dict.clear()
            nextFree = eoi + 1
            codeWidth = minCodeSize + 1
            maxCode1 = 1 shl codeWidth
        }

        val out = ByteArrayOutputStream()
        var acc = 0
        var nbits = 0
        fun emit(code: Int, width: Int) {
            var c = code
            repeat(width) {
                acc = acc or ((c and 1) shl nbits)
                nbits++
                if (nbits == 8) {
                    out.write(acc)
                    acc = 0
                    nbits = 0
                }
                c = c ushr 1
            }
        }

        emit(clear, codeWidth)
        var current = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            if (i % 2048 == 0) yield()
            val k = indices[i].toInt() and 0xFF
            val key = (current shl 8) or k
            val existing = dict[key]
            if (existing != null) {
                current = existing
                continue
            }
            emit(current, codeWidth)
            if (nextFree < LZW_MAX_CODE) {
                dict[key] = nextFree
                nextFree++
                // Deferred code-width increase: the decoder only learns the
                // table filled when it decodes the filling code, one position
                // after the encoder added it (the encoder is permanently one
                // dictionary entry ahead), so widen one add late. Widening at
                // `==` emits a width no deterministic decoder expects.
                if (nextFree > maxCode1) {
                    if (codeWidth < MAX_BITS) {
                        codeWidth++
                        maxCode1 = 1 shl codeWidth
                    } else {
                        emit(clear, codeWidth)
                        resetDictionary()
                    }
                }
            } else {
                emit(clear, codeWidth)
                resetDictionary()
                dict[key] = nextFree
                nextFree++
            }
            current = k
        }
        emit(current, codeWidth)
        emit(eoi, codeWidth)
        if (nbits > 0) out.write(acc)
        return out.toByteArray()
    }

    private fun writeSubBlocks(out: ByteArrayOutputStream, data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val len = minOf(255, data.size - i)
            out.write(len)
            out.write(data, i, len)
            i += len
        }
        out.write(0)
    }

    private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}

/**
 * Extracts keyframes from a stored [clipName] clip and encodes them as a short,
 * low-frame-rate GIF for notification channels.
 *
 * Splits cleanly for tests: frame sampling (Bitmap / MediaMetadataRetriever) is
 * the only Android part — [GifEncoder], paletteing and LZW are plain JVM.
 */
class GifPreviewGenerator(private val context: Context) {

    /**
     * Samples the first [GifPreview.MAX_DURATION_SECONDS] of the clip at the
     * configured [fps], downscaled to at most [maxWidthPx] wide, and returns a
     * GIF [Snapshot] named `<clipStem>.gif`, or null when the clip can't be
     * read (never throws — the fast text alert already went out).
     */
    suspend fun generate(clipName: String, fps: Int, maxWidthPx: Int): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                val source = clipSource(clipName)
                when (source) {
                    is ClipSource.Uri -> retriever.setDataSource(context, source.uri)
                    is ClipSource.File -> retriever.setDataSource(source.path)
                    is ClipSource.Missing -> {
                        Log.w("GifPreview", "clipSource Missing for $clipName")
                        return@runCatching null
                    }
                }
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1000L
                val srcWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0
                val srcHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0
                if (durationUs <= 0L || srcWidth <= 0 || srcHeight <= 0) {
                    Log.w(
                        "GifPreview",
                        "generate: bad header clip=$clipName durationUs=$durationUs (ms=$durationMs) src=${srcWidth}x${srcHeight} fps=$fps maxWidthPx=$maxWidthPx",
                    )
                    return@runCatching null
                }

                // Whole video, x fps decimated to MAX_FRAMES cap and stretched so wall time matches clip.
                val clampedFps = fps.coerceIn(GifPreview.MIN_FPS, GifPreview.MAX_FPS)
                val targetUs = durationUs
                val idealCount = (targetUs * clampedFps / 1_000_000L + 1).toInt()
                val sampleCount = minOf(idealCount, GifPreview.MAX_FRAMES).coerceAtLeast(1)
                val intervalUs = if (sampleCount > 1) targetUs / (sampleCount - 1) else 0L
                val delayCs = if (sampleCount > 1) ((targetUs / 10000L) / sampleCount).toInt().coerceAtLeast(1)
                else (100 / clampedFps).coerceAtLeast(1)

                // Shrink the frame to stay inside the encode pixel budget: user's
                // maxWidthPx stays the ceiling, fps drives sampleCount, and the
                // per-frame pixel budget = totalBudget / sampleCount keeps the LZW
                // encode under withTimeout on the SM_A137F-class device.
                val budgetW = kotlin.math.sqrt(
                    GifPreview.PIXEL_BUDGET.toDouble() / sampleCount * srcWidth / srcHeight,
                ).toInt()
                val width = minOf(
                    maxWidthPx.coerceAtLeast(GifPreview.MIN_WIDTH),
                    budgetW.coerceAtLeast(GifPreview.MIN_WIDTH),
                    srcWidth,
                )
                val height = maxOf(1, srcHeight * width / srcWidth)

                val frames = ArrayList<GifEncoder.Frame>(sampleCount)
                Log.i(
                    "GifPreview",
                    "generate start clip=$clipName durationUs=$durationUs (ideal=$idealCount) src=${srcWidth}x${srcHeight} width=$width height=$height fps=$fps maxWidthPx=$maxWidthPx sampleCount=$sampleCount intervalUs=$intervalUs delayCs=$delayCs",
                )
                var frameWidth = -1
                var frameHeight = -1
                for (i in 0 until sampleCount) {
                    val atUs = minOf(i * intervalUs, durationUs - 1).coerceAtLeast(0L)
                    val bitmap = retriever.getScaledFrameAtTime(
                        atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height,
                    )
                    if (bitmap == null) {
                        Log.w("GifPreview", "getScaledFrameAtTime null clip=$clipName atUs=$atUs i=$i/$sampleCount width=$width height=$height")
                        continue
                    }
                    var useBitmap: Bitmap = bitmap
                    var scaled: Bitmap? = null
                    try {
                        val bw = bitmap.width
                        val bh = bitmap.height
                        if (frameWidth == -1) {
                            frameWidth = bw
                            frameHeight = bh
                        }
                        if (bw != frameWidth || bh != frameHeight) {
                            Log.w(
                                "GifPreview",
                                "frame size mismatch clip=$clipName i=$i bitmap=${bw}x$bh expected=${frameWidth}x${frameHeight} scaling",
                            )
                            scaled = Bitmap.createScaledBitmap(bitmap, frameWidth, frameHeight, true)
                            useBitmap = scaled
                        }
                        val pixels = IntArray(frameWidth * frameHeight)
                        useBitmap.getPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
                        frames.add(GifEncoder.Frame(delayCs, frameWidth, frameHeight, pixels))
                    } catch (e: Exception) {
                        Log.w("GifPreview", "frame $i failed clip=$clipName", e)
                    } finally {
                        scaled?.recycle()
                        bitmap.recycle()
                    }
                }
                if (frames.isEmpty()) {
                    Log.w(
                        "GifPreview",
                        "generate: frames empty clip=$clipName sampleCount=$sampleCount width=$width height=$height durationUs=$durationUs",
                    )
                    return@runCatching null
                }
                if (frames.size == 1) {
                    Log.w(
                        "GifPreview",
                        "single frame clip=$clipName durationUs=$durationUs src=${srcWidth}x${srcHeight} → duplicating to 2 frames",
                    )
                    val first = frames[0]
                    frames.add(first.copy(argb = first.argb.copyOf()))
                }
                Log.i(
                    "GifPreview",
                    "encode start clip=$clipName frames=${frames.size} ${frames[0].width}x${frames[0].height} bytesEst=${frames.size * frames[0].width * frames[0].height}",
                )
                val bytes = try {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(60_000L) {
                            GifEncoder.encode(frames)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("GifPreview", "encode failed clip=$clipName frames=${frames.size}", e)
                    return@runCatching null
                } ?: run {
                    Log.w("GifPreview", "encode timed out clip=$clipName frames=${frames.size}")
                    return@runCatching null
                }
                Log.i("GifPreview", "encode done clip=$clipName bytes=${bytes.size}")
                Snapshot(
                    bytes = bytes,
                    mimeType = "image/gif",
                    name = "${clipName.substringBeforeLast('.')}.gif",
                )
            } finally {
                retriever.release()
            }
        }.onFailure { Log.w("GifPreview", "generate failed for $clipName", it) }.getOrNull()
    }

    private fun clipSource(name: String): ClipSource {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val collection = android.provider.MediaStore.Video.Media.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val uri = runCatching {
            resolver.query(
                collection,
                arrayOf(android.provider.MediaStore.Video.Media._ID),
                "${android.provider.MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(name),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
                } else null
            }
        }.getOrNull()
        if (uri != null) return ClipSource.Uri(uri)
        val fallback = File(appContext.filesDir, "videos/$name")
        return if (fallback.exists()) ClipSource.File(fallback.path) else ClipSource.Missing
    }

    private sealed interface ClipSource {
        data class Uri(val uri: android.net.Uri) : ClipSource
        data class File(val path: String) : ClipSource
        data object Missing : ClipSource
    }
}