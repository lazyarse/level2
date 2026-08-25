package io.securitycam.level2.identity

import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import java.io.File
import org.junit.Assert.assertEquals
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
