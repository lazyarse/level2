package io.securitycam.level2.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wave 2: retention pin matching normalizes clip: prefixes and absolute paths. */
class MediaPinNormalizeTest {

    @Test
    fun stripsClipPrefix() {
        assertEquals("clip1.mp4", OutboxStore.normalizeMediaRef("clip:clip1.mp4"))
    }

    @Test
    fun stripsDirectoryComponents() {
        assertEquals("a.mp4", OutboxStore.normalizeMediaRef("/data/user/0/app/files/videos/a.mp4"))
        assertEquals("b.jpg", OutboxStore.normalizeMediaRef("snapshots/b.jpg"))
    }

    @Test
    fun prefixPlusPath() {
        assertEquals("c.mp4", OutboxStore.normalizeMediaRef("clip:/sdcard/Movies/level2/c.mp4"))
    }

    @Test
    fun plainNameUnchanged() {
        assertEquals("d.mp4", OutboxStore.normalizeMediaRef("d.mp4"))
    }
}
