package io.securitycam.level1.identity

import android.content.Context
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Persists per-person centroid embeddings as bins in
 * `filesDir/known_faces/<id>.bin` (`int32 sampleCount`, then `float32[dim]`
 * little-endian). Enrollment merges a new sample into a running mean and
 * re-normalizes, so centroids stay unit-length.
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

    fun delete(id: String) {
        fileFor(id).delete()
        thumbFileFor(id).delete()
    }

    /** JPEG thumbnail written by [FaceThumbs.writeJpg]; may not exist. */
    fun thumbFileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe face id: $id" }
        return File(facesDir, "$id.jpg")
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe face id: $id" }
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

        fun normalize(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            norm = sqrt(norm)
            if (norm == 0.0) return v.copyOf()
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}
