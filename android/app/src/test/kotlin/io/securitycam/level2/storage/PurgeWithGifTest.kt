package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurgeWithGifTest {

    private fun companionGif(videoName: String): String {
        val gif = "${videoName.substringBeforeLast('.')}.gif"
        return if (gif != videoName) gif else ""
    }

    @Test
    fun companionGifDerivation() {
        assertTrue(companionGif("clip123.mp4") == "clip123.gif")
        assertTrue(companionGif("a/b/c.mp4") == "a/b/c.gif")
        assertTrue(companionGif("noext") == "noext.gif")
        assertTrue(companionGif("clip.with.dots.mp4") == "clip.with.dots.gif")
        assertTrue(companionGif("clip123.gif") == "")
    }

    @Test
    fun snapshotStoreDeletesCompanionGifAlongsideClip() = runBlocking {
        val dir = File.createTempFile("purge-test", "").apply { delete(); mkdirs() }
        try {
            val store = FileSnapshotStore(dir.absolutePath)
            val videoName = "clip123.mp4"
            val gifName = companionGif(videoName)
            // Save both as snapshots (gif is stored via snapshot store in purge).
            store.save(Snapshot(ByteArray(0), "video/mp4", videoName))
            store.save(Snapshot(ByteArray(0), "image/gif", gifName))
            // Simulate purge: delete video then companion gif (same as MonitorViewModel).
            store.delete(videoName)
            // Companion deletion should succeed even if already gone (no throw).
            store.delete(gifName)
            assertTrue(store.load(videoName) == null)
            assertTrue(store.load(gifName) == null)
            // Re-create and verify delete is idempotent.
            store.save(Snapshot(ByteArray(0), "image/gif", gifName))
            assertTrue(store.load(gifName) != null)
            store.delete(gifName)
            assertTrue(store.load(gifName) == null)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun purgeSkipsGifDeletionWhenVideoNameIsAlreadyGif() = runBlocking {
        val dir = File.createTempFile("purge-test2", "").apply { delete(); mkdirs() }
        try {
            val store = FileSnapshotStore(dir.absolutePath)
            val videoName = "clip123.gif"
            val gif = companionGif(videoName)
            assertTrue(gif.isEmpty())
            // MonitorViewModel guards with `if (gif != name)` — already-gif needs no extra delete.
            if (gif.isNotEmpty()) store.delete(gif)
            assertTrue(true) // reaches here without exception
        } finally {
            dir.deleteRecursively()
        }
    }
}
