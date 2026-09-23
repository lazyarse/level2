package io.securitycam.level2.identity

import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnownFaceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): KnownFaceStore = KnownFaceStore(tmp.newFolder("known_faces"))

    @Test
    fun firstEnrollNormalizesAndCountsOne() {
        val s = store()
        val n = s.enroll("p1", floatArrayOf(3f, 4f))
        assertEquals(1, n)
        val centroid = s.load("p1")!!
        assertEquals(0.6f, centroid[0], 1e-6f)
        assertEquals(0.8f, centroid[1], 1e-6f)
    }

    @Test
    fun enrollMergesRunningMeanAndRenormalizes() {
        val s = store()
        s.enroll("p1", floatArrayOf(3f, 4f))
        // Mean of (3,4) and (5,12) is (4,8) -> normalized (0.4472, 0.8944).
        val n = s.enroll("p1", floatArrayOf(5f, 12f))
        assertEquals(2, n)
        val c = s.load("p1")!!
        val norm = kotlin.math.sqrt(80.0)
        assertEquals(4.0 / norm, c[0].toDouble(), 1e-6)
        assertEquals(8.0 / norm, c[1].toDouble(), 1e-6)
    }

    @Test
    fun loadMissingOrDeletedReturnsNull() {
        val s = store()
        assertNull(s.load("nobody"))
        s.enroll("p1", floatArrayOf(1f, 0f))
        s.delete("p1")
        assertNull(s.load("p1"))
    }

    @Test
    fun corruptBinIsToleratedAsNull() {
        val dir = tmp.newFolder("known_faces")
        File(dir, "bad.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(KnownFaceStore(dir).load("bad"))
    }

    @Test
    fun unsafeIdIsRejected() {
        try {
            store().enroll("../evil", floatArrayOf(1f))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ---- Multi-photo gallery (Task 1) ----

    private fun photo(dir: File, id: String, index: Int): File {
        val f = File(dir, "${id}_$index.jpg")
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun listPhotosReturnsIndexSortedJpgsOnly() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        photo(dir, "p1", 1)
        photo(dir, "p1", 0)
        File(dir, "other_0.jpg").writeBytes(byteArrayOf(9))
        File(dir, "p1.bin").writeBytes(byteArrayOf(9))
        val listed = s.listPhotos("p1")
        assertEquals(
            listOf(File(dir, "p1_0.jpg"), File(dir, "p1_1.jpg")),
            listed,
        )
    }

    @Test
    fun legacyJpgMigratesToIndexZeroOnFirstList() {
        val dir = tmp.newFolder("known_faces")
        File(dir, "p1.jpg").writeBytes(byteArrayOf(7, 7))
        val s = KnownFaceStore(dir)
        val listed = s.listPhotos("p1")
        assertEquals(listOf(File(dir, "p1_0.jpg")), listed)
        assertFalse(File(dir, "p1.jpg").exists())
        assertEquals(1, s.photoCount("p1"))
        assertEquals(File(dir, "p1_0.jpg"), s.thumbFileFor("p1"))
    }

    @Test
    fun removeSampleUnlearnsExactVector() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        val e1 = floatArrayOf(3f, 4f)
        val e2 = floatArrayOf(5f, 12f)
        s.enroll("p1", e1)
        s.enroll("p1", e2)
        s.appendSample("p1", 0, e1)
        s.appendSample("p1", 1, e2)
        photo(dir, "p1", 0)
        photo(dir, "p1", 1)
        assertEquals(1, s.removeSample("p1", 1))
        assertEquals(1, s.sampleCount("p1"))
        val c = s.load("p1")!!
        assertEquals(0.6f, c[0], 1e-6f)
        assertEquals(0.8f, c[1], 1e-6f)
        assertEquals(listOf(File(dir, "p1_0.jpg")), s.listPhotos("p1"))
    }

    @Test
    fun removeSampleRefusesLastPhoto() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        s.enroll("p1", floatArrayOf(1f, 0f))
        photo(dir, "p1", 0)
        try {
            s.removeSample("p1", 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, s.sampleCount("p1"))
        assertTrue(File(dir, "p1_0.jpg").exists())
    }

    @Test
    fun legacyPhotoDeleteDropsEntireBase() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        // Two pre-migration samples: only their merged mean survives in the bin.
        s.enroll("p1", floatArrayOf(1f, 0f))
        s.enroll("p1", floatArrayOf(0f, 1f))
        // Legacy thumbnail becomes photo 0 with no .smp entry.
        File(dir, "p1.jpg").writeBytes(byteArrayOf(7))
        val e3 = floatArrayOf(1f, 1f)
        s.enroll("p1", e3)
        s.appendSample("p1", 1, e3)
        photo(dir, "p1", 1)
        assertEquals(2, s.photoCount("p1")) // legacy 0 + new 1
        assertEquals(1, s.removeSample("p1", 0))
        assertEquals(1, s.sampleCount("p1"))
        val c = s.load("p1")!!
        val norm = kotlin.math.sqrt(2.0)
        assertEquals(1.0 / norm, c[0].toDouble(), 1e-6)
        assertEquals(1.0 / norm, c[1].toDouble(), 1e-6)
        assertEquals(listOf(File(dir, "p1_1.jpg")), s.listPhotos("p1"))
    }

    @Test
    fun removeSampleAbortsWhenNothingWouldRemain() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        s.enroll("p1", floatArrayOf(1f, 0f))
        // Inconsistent state (vector stored but never merged): removing it
        // would leave count 0, so the store must refuse.
        s.appendSample("p1", 1, floatArrayOf(0f, 1f))
        photo(dir, "p1", 0)
        photo(dir, "p1", 1)
        try {
            s.removeSample("p1", 1)
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(1, s.sampleCount("p1"))
        assertTrue(File(dir, "p1_1.jpg").exists())
    }

    @Test
    fun deleteClearsAllPhotoArtifacts() {
        val dir = tmp.newFolder("known_faces")
        val s = KnownFaceStore(dir)
        s.enroll("p1", floatArrayOf(1f, 0f))
        s.appendSample("p1", 0, floatArrayOf(1f, 0f))
        photo(dir, "p1", 0)
        photo(dir, "p1", 1)
        assertTrue(File(dir, "p1.smp").exists())
        s.delete("p1")
        assertNull(s.load("p1"))
        assertFalse(File(dir, "p1.bin").exists())
        assertFalse(File(dir, "p1.smp").exists())
        assertFalse(File(dir, "p1_0.jpg").exists())
        assertFalse(File(dir, "p1_1.jpg").exists())
    }

    @Test
    fun knownFacesSettingsRoundTrip() {
        val s = AppSettings.defaults().copyWith(
            knownFaces = listOf(KnownFace(id = "p1", label = "Alice")),
        )
        val back = AppSettings.fromJson(s.toJson())
        assertEquals(1, back.knownFaces.size)
        assertEquals("Alice", back.knownFaces[0].label)
        assertEquals("p1", back.knownFaces[0].id)
    }

    @Test
    fun oldJsonWithoutKnownFacesFallsBackToEmpty() {
        val legacy = AppSettings.defaults().toJson().toMutableMap()
        legacy.remove("knownFaces")
        assertTrue(AppSettings.fromJson(legacy).knownFaces.isEmpty())
    }
}
