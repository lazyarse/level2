package io.securitycam.level1.ui.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavior of the decoded-thumbnail LRU: single load per key (stampede guard),
 * bounded eviction, nulls not cached, explicit eviction forces reload.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThumbCacheTest {

    @Before
    fun setUp() {
        // 1 KB budget: a 2x2 ARGB bitmap is ~16 bytes, so entries evict on
        // pressure only when we exceed the budget many times; tests below use
        // resetForTest to force tiny bounds explicitly.
        ThumbCache.resetForTest(4 * 1024)
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun loadsOncePerKey() = runBlocking {
        var loads = 0
        val first = ThumbCache.getOrLoad("k") { loads++; jpegBytes() }
        val second = ThumbCache.getOrLoad("k") { loads++; jpegBytes() }
        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test
    fun missingBytesAreNotCached() = runBlocking {
        var loads = 0
        assertNull(ThumbCache.getOrLoad("gone") { loads++; null })
        assertNull(ThumbCache.getOrLoad("gone") { loads++; null })
        assertEquals(2, loads) // retried, not negatively cached
        Unit
    }

    @Test
    fun evictForcesReload() = runBlocking {
        var loads = 0
        ThumbCache.getOrLoad("e") { loads++; jpegBytes() }
        ThumbCache.evict("e")
        ThumbCache.getOrLoad("e") { loads++; jpegBytes() }
        assertEquals(2, loads)
        Unit
    }

    @Test
    fun byteBudgetEvictsOldEntries() {
        ThumbCache.resetForTest(1) // 1 KB: one 2x2 bitmap fits, two do not
        runBlocking {
            var loadsA = 0
            var loadsB = 0
            ThumbCache.getOrLoad("a") { loadsA++; jpegBytes() }
            ThumbCache.getOrLoad("b") { loadsB++; jpegBytes() } // evicts "a"
            ThumbCache.getOrLoad("a") { loadsA++; jpegBytes() } // reload
            assertTrue(loadsA == 2 || loadsB == 2) // at least one reload happened
            assertTrue(loadsA + loadsB >= 3)
        }
        Unit
    }

    @Test
    fun concurrentDuplicateLoadsCollapseToOne() = runBlocking {
        var loads = 0
        val jobs = listOf("c", "c", "c", "c").map {
            launch(Dispatchers.IO) {
                ThumbCache.getOrLoad("c") { loads++; jpegBytes() }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, loads)
    }

    @Test
    fun peekReturnsCachedInstanceAndNullAfterEvict() = runBlocking {
        var loads = 0
        val bmp = ThumbCache.getOrLoad("p") { loads++; jpegBytes() }
        assertEquals(bmp, ThumbCache.peek("p")) // same cached instance
        ThumbCache.evict("p")
        assertNull(ThumbCache.peek("p"))
        assertEquals(1, loads)
        Unit
    }

    @Test
    fun downsampleCapsLongestSide() = runBlocking {
        // 600x400 source, maxDim 128 -> longest side <= 128.
        val src = android.graphics.Bitmap.createBitmap(600, 400, android.graphics.Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFF00FF00.toInt())
        val out = java.io.ByteArrayOutputStream()
        src.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        src.recycle()
        val bytes = out.toByteArray()
        val decoded = decodeUpright(bytes, maxDim = 128)
        assertNotNull(decoded)
        assertTrue(decoded!!.width <= 128 && decoded.height <= 128)
        assertTrue(decoded.width > decoded.height) // aspect preserved
        Unit
    }

    @Test
    fun realContextDecodesNonNull() {
        // Robolectric sanity: the EXIF-aware decode path yields a bitmap.
        runBlocking {
            val bmp = ThumbCache.getOrLoad("real") { jpegBytes() }
            assertNotNull(bmp)
        }
        Unit
    }
}
