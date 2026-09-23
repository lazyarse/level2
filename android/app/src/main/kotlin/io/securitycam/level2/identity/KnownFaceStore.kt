package io.securitycam.level2.identity

import android.content.Context
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Persists per-person centroid embeddings as bins in
 * `filesDir/known_faces/<id>.bin` (`int32 sampleCount`, then `float32[dim]`
 * little-endian). Enrollment merges a new sample into a running mean and
 * re-normalizes, so centroids stay unit-length.
 *
 * Multi-photo gallery: `<id>_<index>.jpg` photos (index-sorted via
 * [listPhotos]) plus a `<id>.smp` journal (`int32 version`, `int32 count`,
 * then per entry `int32 photoIndex`, `int32 dim`, `float32[dim]`,
 * little-endian) holding one raw embedding per stored photo. Deleting a photo
 * ([removeSample]) subtracts its sample from the centroid; the lone legacy
 * `<id>.jpg` migrates to `<id>_0.jpg` on first access and carries no journal
 * entry (its vector was never stored raw).
 */
class KnownFaceStore(private val facesDir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    /** Merges [embedding] into person [id]'s centroid; returns the sample count. */
    @Synchronized
    fun enroll(id: String, embedding: FloatArray): Int {
        require(embedding.isNotEmpty()) { "empty embedding" }
        val existing = readOrNull(id)
        val (centroid, count) = if (existing == null) {
            embedding.copyOf() to 1
        } else {
            val (mean, n0) = existing
            require(mean.size == embedding.size) { "embedding dimension mismatch" }
            val n = n0 + 1
            // Running mean over raw samples; normalization happens on [load].
            FloatArray(mean.size) { i -> (mean[i] * n0 + embedding[i]) / n } to n
        }
        writeBin(fileFor(id), centroid, count)
        return count
    }

    /** The stored centroid for [id], L2-normalized, or null when absent/corrupt. */
    @Synchronized
    fun load(id: String): FloatArray? = readRaw(id)?.let(::normalize)

    /** Number of merged samples for [id] (0 when absent/corrupt). */
    @Synchronized
    fun sampleCount(id: String): Int = try {
        readBinStrict(fileFor(id)).second
    } catch (_: IOException) {
        0
    }

    fun delete(id: String) {
        validateId(id)
        fileFor(id).delete()
        smpFileFor(id).delete()
        listPhotos(id).forEach { it.delete() }
        File(facesDir, "$id.jpg").delete()
    }

    /** Row thumbnail: first gallery photo, or the legacy path pre-migration. */
    @Synchronized
    fun thumbFileFor(id: String): File {
        validateId(id)
        migrateLegacyJpg(id)
        return listPhotos(id).firstOrNull() ?: File(facesDir, "$id.jpg")
    }

    /** Gallery photos for [id], sorted by index; runs the legacy migration. */
    @Synchronized
    fun listPhotos(id: String): List<File> {
        validateId(id)
        migrateLegacyJpg(id)
        val prefix = "${id}_"
        val files = facesDir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".jpg")
        } ?: return emptyList()
        return files.mapNotNull { f ->
            photoIndexOf(id, f)?.let { it to f }
        }.sortedBy { it.first }.map { it.second }
    }

    /** Next free gallery index for [id] (0 when no photos). */
    @Synchronized
    fun nextPhotoIndex(id: String): Int =
        listPhotos(id).mapNotNull { photoIndexOf(id, it) }.maxOrNull()?.plus(1) ?: 0

    private fun photoIndexOf(id: String, file: File): Int? =
        Regex("^" + Regex.escape(id) + "_(\\d+)\\.jpg$")
            .matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()

    /** Number of gallery photos for [id] (0 when none). */
    @Synchronized
    fun photoCount(id: String): Int = listPhotos(id).size

    /** File for photo [index] of [id] (may not exist). */
    fun photoFileFor(id: String, index: Int): File {
        validateId(id)
        require(index >= 0) { "negative photo index: $index" }
        return File(facesDir, "${id}_$index.jpg")
    }

    /** Journals one raw sample embedding for photo [index] (overwrites it). */
    @Synchronized
    fun appendSample(id: String, index: Int, embedding: FloatArray) {
        validateId(id)
        require(index >= 0) { "negative photo index: $index" }
        require(embedding.isNotEmpty()) { "empty embedding" }
        val entries = readSmp(id).toMutableList()
        val dim = entries.firstOrNull()?.second?.size
        if (dim != null) require(embedding.size == dim) { "embedding dimension mismatch" }
        require(entries.none { it.first == index }) { "duplicate photo index: $index" }
        entries += index to embedding.copyOf()
        writeSmp(id, entries)
    }

    /**
     * Deletes photo [index] and subtracts its sample from the centroid;
     * returns the new sample count. Refuses the last photo. A photo without
     * a journal entry (the migrated legacy thumbnail) drops the whole
     * legacy aggregate — that image is the overwritten residue of every
     * pre-migration capture. Aborts with [IllegalStateException] (files
     * untouched) when the recompute would be degenerate.
     */
    @Synchronized
    fun removeSample(id: String, index: Int): Int {
        validateId(id)
        val photos = listPhotos(id)
        require(photos.size >= 2) { "cannot remove the last photo" }
        val target = photoFileFor(id, index)
        require(photos.any { it.name == target.name }) { "unknown photo index: $index" }
        val (mean, n) = readBinStrict(fileFor(id))
        val entries = readSmp(id)
        val entry = entries.firstOrNull { it.first == index }
        val (newMean, newCount) = if (entry != null) {
            require(entry.second.size == mean.size) { "embedding dimension mismatch" }
            val next = n - 1
            check(next >= 1) { "unlearn would empty the centroid" }
            FloatArray(mean.size) { i -> (mean[i] * n - entry.second[i]) / next } to next
        } else {
            val sum = FloatArray(mean.size)
            for ((_, v) in entries) {
                require(v.size == mean.size) { "embedding dimension mismatch" }
                for (i in sum.indices) sum[i] += v[i]
            }
            val legacy = n - entries.size
            check(legacy >= 0) { "sample journal exceeds centroid count" }
            check(entries.isNotEmpty()) { "nothing recognisable would remain" }
            val next = n - legacy
            check(next >= 1) { "unlearn would empty the centroid" }
            FloatArray(mean.size) { i -> sum[i] / next } to next
        }
        check(newMean.all { it.isFinite() }) { "unlearn produced a degenerate centroid" }
        writeBin(fileFor(id), newMean, newCount)
        if (entry != null) writeSmp(id, entries.filterNot { it.first == index })
        target.delete()
        return newCount
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe face id: $id" }
    }

    /** One-time move of the pre-gallery single thumbnail into the gallery. */
    private fun migrateLegacyJpg(id: String) {
        val legacy = File(facesDir, "$id.jpg")
        if (!legacy.isFile) return
        val target = File(facesDir, "${id}_0.jpg")
        if (target.exists()) return
        runCatching { legacy.renameTo(target) }
    }

    private fun smpFileFor(id: String): File {
        validateId(id)
        return File(facesDir, "$id.smp")
    }

    private fun readSmp(id: String): List<Pair<Int, FloatArray>> {
        val file = smpFileFor(id)
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size < 8) throw IOException("corrupt smp")
        var off = 0
        val version = readLe32(bytes, off)
        off += 4
        if (version != SMP_VERSION) throw IOException("unsupported smp version: $version")
        val count = readLe32(bytes, off)
        off += 4
        if (count < 0) throw IOException("corrupt smp header")
        val entries = ArrayList<Pair<Int, FloatArray>>(count)
        repeat(count) {
            if (off + 8 > bytes.size) throw IOException("corrupt smp")
            val index = readLe32(bytes, off)
            off += 4
            val dim = readLe32(bytes, off)
            off += 4
            if (index < 0 || dim <= 0 || off + dim * 4 > bytes.size) {
                throw IOException("corrupt smp")
            }
            val vector = FloatArray(dim) { i ->
                Float.fromBits(readLe32(bytes, off + i * 4))
            }
            off += dim * 4
            entries += index to vector
        }
        return entries
    }

    private fun writeSmp(id: String, entries: List<Pair<Int, FloatArray>>) {
        facesDir.mkdirs()
        val out = ByteArray(8 + entries.sumOf { 8 + it.second.size * 4 })
        var off = 0
        writeLe32(out, off, SMP_VERSION)
        off += 4
        writeLe32(out, off, entries.size)
        off += 4
        for ((index, vector) in entries) {
            writeLe32(out, off, index)
            off += 4
            writeLe32(out, off, vector.size)
            off += 4
            for (v in vector) {
                writeLe32(out, off, v.toRawBits())
                off += 4
            }
        }
        smpFileFor(id).writeBytes(out)
    }

    private fun readLe32(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(out: ByteArray, off: Int, value: Int) {
        out[off] = (value and 0xFF).toByte()
        out[off + 1] = ((value shr 8) and 0xFF).toByte()
        out[off + 2] = ((value shr 16) and 0xFF).toByte()
        out[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun fileFor(id: String): File {
        validateId(id)
        facesDir.mkdirs()
        return File(facesDir, "$id.bin")
    }

    private fun readOrNull(id: String): Pair<FloatArray, Int>? = try {
        readBinStrict(fileFor(id))
    } catch (_: IOException) {
        null
    }

    private fun readRaw(id: String): FloatArray? = try {
        readBinStrict(fileFor(id)).first
    } catch (_: IOException) {
        null
    }

    private fun readBinStrict(file: File): Pair<FloatArray, Int> {
        if (!file.exists()) throw IOException("missing bin")
        val bytes = file.readBytes()
        if (bytes.size < 8 || (bytes.size - 4) % 4 != 0) throw IOException("corrupt bin")
        var off = 0
        fun le32(): Int {
            val v = (bytes[off].toInt() and 0xFF) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                ((bytes[off + 3].toInt() and 0xFF) shl 24)
            off += 4
            return v
        }
        val count = le32()
        if (count <= 0) throw IOException("corrupt bin header")
        val floats = FloatArray((bytes.size - 4) / 4)
        for (i in floats.indices) floats[i] = Float.fromBits(le32())
        return floats to count
    }

    private fun writeBin(file: File, floats: FloatArray, count: Int) {
        val out = ByteArray(4 + floats.size * 4)
        out[0] = (count and 0xFF).toByte()
        out[1] = ((count shr 8) and 0xFF).toByte()
        out[2] = ((count shr 16) and 0xFF).toByte()
        out[3] = ((count shr 24) and 0xFF).toByte()
        for ((i, v) in floats.withIndex()) {
            val bits = v.toRawBits()
            val off = 4 + i * 4
            out[off] = (bits and 0xFF).toByte()
            out[off + 1] = ((bits shr 8) and 0xFF).toByte()
            out[off + 2] = ((bits shr 16) and 0xFF).toByte()
            out[off + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        file.writeBytes(out)
    }

    companion object {
        const val DIR_NAME = "known_faces"

        /** Version stamp of the `<id>.smp` sample journal format. */
        private const val SMP_VERSION = 1

        fun normalize(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            norm = sqrt(norm)
            if (norm == 0.0) return v.copyOf()
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}
