package io.securitycam.level2.camera_service

/**
 * Bounded rolling buffer of mono s16le mic PCM. Chunks arrive on the mic read
 * thread with their absolute start sample; the oldest chunk outside the rolling
 * window is dropped. [slice] returns a zero-filled contiguous window covering
 * [startSample, endSample), synthesizing silence where no mic data exists.
 */
class AudioPcmBuffer(
    val sampleRate: Int,
    private val windowSamples: Long,
) {
    private class Chunk(val pcm: ByteArray, val startSample: Long)

    private val lock = Object()
    private val chunks = ArrayList<Chunk>()
    private var lastSampleInternal = 0L

    val lastSample: Long
        get() = synchronized(lock) { lastSampleInternal }

    val hasData: Boolean
        get() = synchronized(lock) { chunks.isNotEmpty() }

    fun add(pcm: ByteArray, startSample: Long) {
        val endSample = startSample + pcm.size / 2
        synchronized(lock) {
            chunks.add(Chunk(pcm, startSample))
            if (lastSampleInternal < endSample) lastSampleInternal = endSample
            val minStart = endSample - windowSamples
            while (chunks.isNotEmpty() && chunks[0].startSample < minStart) {
                chunks.removeAt(0)
            }
        }
    }

    fun slice(startSample: Long, endSample: Long): ByteArray {
        if (endSample <= startSample) return ByteArray(0)
        val lengthSamples = (endSample - startSample).coerceIn(0L, Int.MAX_VALUE / 2L)
        val out = ByteArray((lengthSamples * 2L).toInt())
        synchronized(lock) {
            for (chunk in chunks) {
                if (chunk.startSample >= endSample) break
                val chunkEnd = chunk.startSample + chunk.pcm.size / 2
                if (chunkEnd <= startSample) continue
                val from = maxOf(chunk.startSample, startSample)
                val to = minOf(chunkEnd, endSample)
                val byteOffset = ((from - chunk.startSample).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                    .coerceIn(0, chunk.pcm.size)
                val byteLen = ((to - from).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                    .coerceIn(0, chunk.pcm.size - byteOffset)
                val outByteOffset = ((from - startSample).coerceIn(0L, Int.MAX_VALUE / 2L) * 2L).toInt()
                if (outByteOffset + byteLen > out.size) continue
                System.arraycopy(
                    chunk.pcm, byteOffset,
                    out, outByteOffset, byteLen,
                )
            }
        }
        return out
    }

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            lastSampleInternal = 0L
        }
    }
}
