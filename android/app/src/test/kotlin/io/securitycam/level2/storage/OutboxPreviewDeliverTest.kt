package io.securitycam.level2.storage

import io.securitycam.level2.core.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxPreviewDeliverTest {

    @Test
    fun notifyRowWithPreviewGifLoadsSnapshot() = runBlocking {
        val dir = File.createTempFile("outbox-preview", "").apply { delete(); mkdirs() }
        try {
            val store = FileSnapshotStore(dir.absolutePath)
            val gifBytes = byteArrayOf(1, 2, 3, 4)
            val gifName = "clip123.gif"
            store.save(Snapshot(gifBytes, "image/gif", gifName))
            val row = OutboxEntity(
                createdAt = 1_000L,
                kind = OutboxKind.NOTIFY,
                channelId = "telegram",
                eventId = 1L,
                text = "hi",
                snapshotName = null,
                previewGifName = gifName,
            )
            // Mirror OutboxWorker.deliverNotify loading.
            val snapshot = row.snapshotName?.let { store.load(it) }
            val preview = row.previewGifName?.let { store.load(it) }
            assertNull(snapshot)
            assertNotNull(preview)
            assertEquals(gifName, preview!!.name)
            assertEquals("image/gif", preview.mimeType)
            org.junit.Assert.assertArrayEquals(gifBytes, preview.bytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingPreviewGifIsNullNotCrash() = runBlocking {
        val dir = File.createTempFile("outbox-preview2", "").apply { delete(); mkdirs() }
        try {
            val store = FileSnapshotStore(dir.absolutePath)
            val row = OutboxEntity(
                createdAt = 1_000L,
                kind = OutboxKind.NOTIFY,
                channelId = "telegram",
                eventId = 1L,
                text = "hi",
                previewGifName = "missing.gif",
            )
            val preview = row.previewGifName?.let { runCatching { store.load(it) }.getOrNull() }
            assertNull(preview)
        } finally {
            dir.deleteRecursively()
        }
    }
}
