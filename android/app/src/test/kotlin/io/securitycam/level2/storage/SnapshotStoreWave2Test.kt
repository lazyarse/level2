package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Wave 2: collision-safe naming, case-insensitive mime, atomic writes. */
class SnapshotStoreWave2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileSnapshotStore = FileSnapshotStore(tmp.newFolder().absolutePath)

    @Test
    fun sanitizerCollisionsDoNotOverwrite() = runBlocking {
        val s = store()
        // Both sanitize to "a_b.png" under the old scheme.
        s.save(Snapshot(byteArrayOf(1), "image/png", "a/b.png"))
        s.save(Snapshot(byteArrayOf(2), "image/png", "a_b.png"))

        assertEquals(listOf<Byte>(1), s.load("a/b.png")!!.bytes.toList())
        assertEquals(listOf<Byte>(2), s.load("a_b.png")!!.bytes.toList())
    }

    @Test
    fun mimeSniffIsCaseInsensitive() = runBlocking {
        val s = store()
        s.save(Snapshot(byteArrayOf(1), "image/png", "UPPER.PNG"))
        s.save(Snapshot(byteArrayOf(2), "image/jpeg", "mixed.JPEG"))
        s.save(Snapshot(byteArrayOf(3), "image/jpeg", "photo.JPG"))

        assertEquals("image/png", s.load("UPPER.PNG")!!.mimeType)
        assertEquals("image/jpeg", s.load("mixed.JPEG")!!.mimeType)
        assertEquals("image/jpeg", s.load("photo.JPG")!!.mimeType)
    }

    @Test
    fun saveLeavesNoTempFilesBehind() = runBlocking {
        val dir = tmp.newFolder()
        val s = FileSnapshotStore(dir.absolutePath)
        s.save(Snapshot(byteArrayOf(1, 2, 3), "image/png", "a.png"))

        val leftovers = dir.listFiles()!!.filter { it.name.endsWith(".tmp") || it.name.startsWith("snap-") }
        assertTrue(leftovers.isEmpty())
        assertEquals(listOf<Byte>(1, 2, 3), s.load("a.png")!!.bytes.toList())
        // Round-trip path stays inside the store directory.
        assertTrue(File(s.save(Snapshot(byteArrayOf(9), "image/png", "b.png"))).parent == dir.absolutePath)
    }
}
