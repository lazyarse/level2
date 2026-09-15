package io.securitycam.level2.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM round-trip tests for [GifEncoder]: byte-level structure plus a
 * small in-test GIF parser/LZW decoder that mirrors the encoder's deferred
 * code-width transitions (widen when next free slot reaches `1 shl codeSize`,
 * GIFLIB-style), so a passing decode proves the emitted stream is valid.
 */
class GifEncoderTest {

    private data class ParsedFrame(val delayCs: Int, val minCodeSize: Int, val data: ByteArray)
    private data class ParsedGif(
        val width: Int,
        val height: Int,
        val gct: ByteArray,
        val frames: List<ParsedFrame>,
    )

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun skipSubBlocks(b: ByteArray, from: Int): Int {
        var p = from
        while (true) {
            val n = b[p++].toInt() and 0xFF
            if (n == 0) return p
            p += n
        }
    }

    private fun parse(bytes: ByteArray): ParsedGif {
        var p = 6 // "GIF89a"
        val w = le16(bytes, p)
        val h = le16(bytes, p + 2)
        val packed = bytes[p + 4].toInt() and 0xFF
        assertTrue("GCT flag must be set", packed and 0x80 != 0)
        val gctEntries = 1 shl ((packed and 0x07) + 1)
        p += 7
        val gct = bytes.copyOfRange(p, p + gctEntries * 3)
        p += gctEntries * 3
        val frames = mutableListOf<ParsedFrame>()
        var delay = 0
        while (p < bytes.size) {
            when (bytes[p].toInt() and 0xFF) {
                0x3B -> break
                0x21 -> {
                    val label = bytes[p + 1].toInt() and 0xFF
                    if (label == 0xF9) {
                        delay = le16(bytes, p + 4)
                        p += 8
                    } else {
                        p = skipSubBlocks(bytes, p + 2)
                    }
                }
                0x2C -> {
                    p += 10 // image descriptor
                    val min = bytes[p++].toInt() and 0xFF
                    val buf = ByteArrayOutputStream()
                    while (true) {
                        val n = bytes[p++].toInt() and 0xFF
                        if (n == 0) break
                        buf.write(bytes, p, n)
                        p += n
                    }
                    frames.add(ParsedFrame(delay, min, buf.toByteArray()))
                }
                else -> error("unexpected block 0x%02x at %d".format(bytes[p], p))
            }
        }
        return ParsedGif(w, h, gct, frames)
    }

    /** GIF-variant LZW decoder mirroring [GifEncoder.lzw]'s width transitions. */
    private fun lzwDecode(minCodeSize: Int, data: ByteArray): List<Int> {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        val dict = ArrayList<MutableList<Int>>()
        fun reset() {
            dict.clear()
            for (i in 0 until clear) dict.add(mutableListOf(i))
            dict.add(mutableListOf())
            dict.add(mutableListOf())
            codeSize = minCodeSize + 1
        }
        reset()
        var next = eoi + 1
        val out = ArrayList<Int>()
        var datum = 0
        var bits = 0
        var pos = 0
        fun read(): Int {
            while (bits < codeSize) {
                datum = datum or ((data[pos++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            val c = datum and ((1 shl codeSize) - 1)
            datum = datum ushr codeSize
            bits -= codeSize
            return c
        }
        var prev: List<Int>? = null
        while (true) {
            val code = read()
            if (code == clear) {
                reset()
                next = eoi + 1
                prev = null
                continue
            }
            if (code == eoi) break
            val entry: List<Int> =
                if (code < next) dict[code].toList() else prev!! + prev!!.first()
            out.addAll(entry)
            val pr = prev
            if (pr != null) {
                dict.add((pr + entry.first()).toMutableList())
                next++
                if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = entry
        }
        return out
    }

    private fun solidFrame(w: Int, h: Int, argb: Int, delayCs: Int = 10) =
        GifEncoder.Frame(delayCs, w, h, IntArray(w * h) { argb })

    @Test
    fun encodeRequiresAtLeastOneFrame() = runBlocking {
        try {
            GifEncoder.encode(emptyList())
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun mismatchedFrameDimensionsThrow() = runBlocking {
        try {
            GifEncoder.encode(
                listOf(solidFrame(4, 4, 0xFFFF0000.toInt()), solidFrame(2, 2, 0xFF00FF00.toInt())),
            )
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun headerTrailerAndDimensions() = runBlocking {
        val bytes = GifEncoder.encode(listOf(solidFrame(4, 3, 0xFFFF0000.toInt())))
        assertArrayEquals("GIF89a".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, 6))
        assertEquals(4, le16(bytes, 6))
        assertEquals(3, le16(bytes, 8))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    @Test
    fun netscapeLoopExtensionPresent() = runBlocking {
        val bytes = GifEncoder.encode(listOf(solidFrame(2, 2, 0xFF0000FF.toInt())))
        val needle = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        val found = bytes.indices.any { i ->
            i + needle.size <= bytes.size &&
                needle.indices.all { j -> bytes[i + j] == needle[j] }
        }
        assertTrue("NETSCAPE2.0 application extension must be present", found)
    }

    @Test
    fun graphicControlDelayRoundTripsPerFrame() = runBlocking {
        val bytes = GifEncoder.encode(
            listOf(
                solidFrame(2, 2, 0xFFFF0000.toInt(), delayCs = 25),
                solidFrame(2, 2, 0xFF00FF00.toInt(), delayCs = 100),
            ),
        )
        val parsed = parse(bytes)
        assertEquals(listOf(25, 100), parsed.frames.map { it.delayCs })
    }

    @Test
    fun solidColorFrameDecodesUniformlyToPaletteRed() = runBlocking {
        val bytes = GifEncoder.encode(listOf(solidFrame(2, 2, 0xFFFF0000.toInt())))
        val parsed = parse(bytes)
        assertEquals(1, parsed.frames.size)
        val indices = lzwDecode(parsed.frames[0].minCodeSize, parsed.frames[0].data)
        assertEquals(4, indices.size)
        assertEquals(1, indices.toSet().size)
        val idx = indices[0]
        assertEquals(255, parsed.gct[idx * 3].toInt() and 0xFF)
        assertEquals(0, parsed.gct[idx * 3 + 1].toInt() and 0xFF)
        assertEquals(0, parsed.gct[idx * 3 + 2].toInt() and 0xFF)
    }

    @Test
    fun manyColorFrameClampsPaletteAndDecodesFullPixelCount() = runBlocking {
        val w = 20
        val h = 20
        // >256 distinct colours forces median-cut quantisation.
        val argb = IntArray(w * h) { i ->
            val r = i and 0xFF
            val g = (i shr 8) and 0xFF
            val b = (i * 29) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        assertTrue(argb.toSet().size > 256)
        assertTrue(GifEncoder.globalPalette(listOf(GifEncoder.Frame(10, w, h, argb))).size <= 256)
        val bytes = GifEncoder.encode(listOf(GifEncoder.Frame(10, w, h, argb)))
        val parsed = parse(bytes)
        assertTrue(parsed.frames[0].minCodeSize <= 8)
        val indices = lzwDecode(parsed.frames[0].minCodeSize, parsed.frames[0].data)
        assertEquals(w * h, indices.size)
        assertTrue(indices.all { it in 0..255 })
    }

    @Test
    fun bitsForColorCountDepths() = runBlocking {
        assertEquals(2, GifEncoder.bitsForColorCount(1))
        assertEquals(2, GifEncoder.bitsForColorCount(2))
        assertEquals(2, GifEncoder.bitsForColorCount(4))
        assertEquals(3, GifEncoder.bitsForColorCount(5))
        assertEquals(4, GifEncoder.bitsForColorCount(16))
        assertEquals(5, GifEncoder.bitsForColorCount(17))
        assertEquals(8, GifEncoder.bitsForColorCount(256))
        assertEquals(8, GifEncoder.bitsForColorCount(1000))
    }

    @Test(timeout = 5000)
    fun twoIdenticalFramesEncodeQuickly() = runBlocking {
        val f1 = solidFrame(320, 213, 0xFFFF0000.toInt(), delayCs = 50)
        val f2 = GifEncoder.Frame(50, 320, 213, f1.argb.copyOf())
        val bytes = GifEncoder.encode(listOf(f1, f2))
        assertTrue(bytes.isNotEmpty())
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        val parsed = parse(bytes)
        assertEquals(2, parsed.frames.size)
        assertEquals(50, parsed.frames[0].delayCs)
        assertEquals(50, parsed.frames[1].delayCs)
    }

    @Test(timeout = 10000)
    fun twoManyColorFramesEncodeQuickly() = runBlocking {
        val w = 320
        val h = 213
        val argb = IntArray(w * h) { i ->
            val r = (i * 13) % 256
            val g = (i * 7) % 256
            val b = (i * 29) % 256
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val f1 = GifEncoder.Frame(50, w, h, argb)
        val f2 = GifEncoder.Frame(50, w, h, argb.copyOf())
        val start = System.currentTimeMillis()
        val bytes = GifEncoder.encode(listOf(f1, f2))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("encode took $elapsed ms", elapsed < 5000)
        assertTrue(bytes.isNotEmpty())
        val parsed = parse(bytes)
        assertEquals(2, parsed.frames.size)
    }

    @Test(timeout = 30000)
    fun medianCutWithDominantLastColorTerminates() = runBlocking {
        // Regression: when the weighted median lands on a box's last element
        // there is no valid split; the cutter must stop, not re-loop forever
        // (on-device this hung until the 60 s encode withTimeout).
        val argb = ArrayList<Int>(50501)
        for (r in 0..254) argb.add((0xFF shl 24) or (r shl 16))
        argb.add((0xFF shl 24) or (1 shl 8)) // lone green, keeps red the longest axis
        repeat(50000) { argb.add((0xFF shl 24) or (0xFF shl 16)) } // dominant red, sorts last
        val frame = GifEncoder.Frame(34, argb.size, 1, argb.toIntArray())
        val palette = GifEncoder.globalPalette(listOf(frame))
        assertTrue(palette.isNotEmpty())
        assertTrue(palette.size <= 256)
        val bytes = GifEncoder.encode(listOf(frame))
        assertTrue(bytes.isNotEmpty())
    }

    @Test(timeout = 30000)
    fun richGradientYieldsRichPalette() = runBlocking {
        // Regression for the ~16-colour previews: a smoothly varying clip
        // must quantise to a rich palette (unsplittable boxes retire
        // individually instead of freezing the whole cut).
        val w = 71
        val h = 106
        val frames = (0 until 60).map { f ->
            GifEncoder.Frame(
                34, w, h,
                IntArray(w * h) { i ->
                    val x = i % w
                    val y = i / w
                    val r = (x * 3 + y + f * 2) % 256
                    val g = (y * 2 + f * 3) % 256
                    val b = (x + y * 2 + f) % 256
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                },
            )
        }
        val palette = GifEncoder.globalPalette(frames)
        assertTrue("palette too thin: ${palette.size}", palette.size > 100)
        assertTrue(palette.size <= 256)
        val bytes = GifEncoder.encode(frames)
        assertTrue(bytes.isNotEmpty())
        assertEquals(60, parse(bytes).frames.size)
    }
    @Test(timeout = 30000)
    fun sixtyNoisyFramesStayWithinPaletteAndEncode() = runBlocking {
        // Mirrors the on-device failure: 60 whole-clip frames must quantise to
        // <= 256 colours and finish encoding (stride-sampled palette input).
        val w = 71
        val h = 106
        val frames = (0 until 60).map { f ->
            GifEncoder.Frame(
                34, w, h,
                IntArray(w * h) { i ->
                    val r = (i * 13 + f * 7) % 256
                    val g = (i * 7 + f * 29) % 256
                    val b = (i * 29 + f * 13) % 256
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                },
            )
        }
        assertTrue(GifEncoder.globalPalette(frames).size <= 256)
        val start = System.currentTimeMillis()
        val bytes = GifEncoder.encode(frames)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("60-frame encode took $elapsed ms", elapsed < 20000)
        assertTrue(bytes.isNotEmpty())
        val parsed = parse(bytes)
        assertEquals(60, parsed.frames.size)
        assertTrue(parsed.frames.all { it.delayCs == 34 })
    }
}
