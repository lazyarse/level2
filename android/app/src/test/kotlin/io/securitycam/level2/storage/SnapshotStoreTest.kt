package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Port of `test/snapshot_store_test.dart` semantics (file IO). */
class SnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileSnapshotStore = FileSnapshotStore(tmp.newFolder().absolutePath)

    @Test
    fun saveWritesBytesAndReturnsThePath() = runBlocking {
        val s = store()
        val path = s.save(Snapshot(byteArrayOf(1, 2, 3), "image/png", "snap.png"))
        assertTrue(File(path).exists())
        assertEquals(3, File(path).length())
    }

    @Test
    fun loadReturnsTheSnapshotWithInferredMime() = runBlocking {
        val s = store()
        s.save(Snapshot(byteArrayOf(9, 8), "image/png", "a.png"))
        s.save(Snapshot(byteArrayOf(7), "image/jpeg", "b.jpg"))

        val png = s.load("a.png")
        assertEquals("image/png", png!!.mimeType)
        assertEquals(byteArrayOf(9, 8).toList(), png.bytes.toList())

        val jpg = s.load("b.jpg")
        assertEquals("image/jpeg", jpg!!.mimeType)
    }

    @Test
    fun loadOfAMissingNameReturnsNull() = runBlocking {
        assertNull(store().load("nope.png"))
    }

    @Test
    fun deleteRemovesTheFile() = runBlocking {
        val s = store()
        s.save(Snapshot(byteArrayOf(1), "image/png", "gone.png"))
        s.delete("gone.png")
        assertNull(s.load("gone.png"))
    }

    @Test
    fun unsafeNamesAreSanitized() = runBlocking {
        val s = store()
        s.save(Snapshot(byteArrayOf(1), "image/png", "../../etc/passwd.png"))
        // The sanitized file lives inside the store directory.
        val loaded = s.load("../../etc/passwd.png")
        assertTrue(loaded != null)
        assertTrue(File(File(tmp.root.absolutePath).listFiles()!!.single().absolutePath).exists())
    }
}