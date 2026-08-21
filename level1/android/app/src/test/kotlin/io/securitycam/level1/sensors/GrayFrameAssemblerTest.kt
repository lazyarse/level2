package io.securitycam.level1.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `test/gray_frame_assembler_test.dart`. */
class GrayFrameAssemblerTest {

    @Test
    fun emitsAFrameFromASingleExactSizeChunk() {
        val a = GrayFrameAssembler(2, 2)
        val frames = a.add(byteArrayOf(1, 2, 3, 4))
        assertEquals(1, frames.size)
        assertEquals(listOf(1, 2, 3, 4), frames.single().gray.map { it.toInt() and 0xFF })
        assertEquals(0, a.buffered)
    }

    @Test
    fun emitsFramesFromArbitraryByteSplitChunks() {
        val a = GrayFrameAssembler(2, 2)
        val input = Array(12) { it.toByte() } // 3 frames of 4 bytes
        val frames = input.flatMap { a.add(byteArrayOf(it)) }
        assertEquals(3, frames.size)
        assertEquals(listOf(0, 1, 2, 3), frames[0].gray.map { it.toInt() and 0xFF })
        assertEquals(listOf(8, 9, 10, 11), frames[2].gray.map { it.toInt() and 0xFF })
        assertEquals(0, a.buffered)
    }

    @Test
    fun oneChunkContainingMultipleFramesEmitsAllOfThem() {
        val a = GrayFrameAssembler(2, 1)
        val frames = a.add(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(3, frames.size)
        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)),
            frames.map { f -> f.gray.map { it.toInt() and 0xFF } },
        )
    }

    @Test
    fun partialChunkCarriesRemainderAcrossCalls() {
        val a = GrayFrameAssembler(2, 2)
        assertEquals(emptyList<Any>(), a.add(byteArrayOf(1, 2, 3)))
        assertEquals(3, a.buffered)
        val frames = a.add(byteArrayOf(4, 5))
        assertEquals(1, frames.size)
        assertEquals(listOf(1, 2, 3, 4), frames.single().gray.map { it.toInt() and 0xFF })
        assertEquals(1, a.buffered)
        assertEquals(emptyList<Any>(), a.add(byteArrayOf(6)))
        assertEquals(2, a.buffered)
        val rest = a.add(byteArrayOf(7, 8))
        assertEquals(1, rest.size)
        assertEquals(listOf(5, 6, 7, 8), rest.single().gray.map { it.toInt() and 0xFF })
        assertEquals(0, a.buffered)
    }
}