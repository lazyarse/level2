package io.securitycam.level1.identity

import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.face.FaceDetection
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FaceEnrollmentCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val frame = ColorBitmap(100, 100, ByteArray(3 * 100 * 100))
    private val face = FaceDetection(10.0, 10.0, 40.0, 40.0, 0.9)

    private class FakeEmbedder : io.securitycam.level1.detection.face.FaceEmbedder {
        override fun embed(
            f: ColorBitmap,
            box: DoubleArray,
        ): FloatArray = floatArrayOf(1f, 0f, 0f)
    }

    private fun coordinator(
        store: KnownFaceStore,
        finder: FaceFinder,
        embedder: io.securitycam.level1.detection.face.FaceEmbedder? = FakeEmbedder(),
    ): Pair<FaceEnrollmentCoordinator, MutableList<AppSettings>> {
        var settings = AppSettings.defaults()
        val saves = mutableListOf<AppSettings>()
        val c = FaceEnrollmentCoordinator(
            store = store,
            embedder = embedder,
            faceFinder = finder,
            settingsLoader = { settings },
            settingsSaver = { saves.add(it); settings = it },
        )
        return c to saves
    }

    @Test
    fun enrollAddsKnownFaceAndPersistsCentroid() = runBlocking {
        val dir = tmp.newFolder("kf")
        val (c, saves) = coordinator(KnownFaceStore(dir), { frame to face })
        val result = c.enroll("Alice")
        val person = result.getOrThrow()
        assertEquals("Alice", person.label)
        assertTrue(person.id.startsWith("face_"))
        assertEquals(1, saves.size)
        assertEquals(1, saves.last().knownFaces.size)
        assertTrue(KnownFaceStore(dir).load(person.id) != null)
    }

    @Test
    fun reEnrollSameLabelFoldsIntoExistingCentroid() = runBlocking {
        val dir = tmp.newFolder("kf")
        val store = KnownFaceStore(dir)
        // Shared mutable settings so both coordinators see enrollments.
        var settings = AppSettings.defaults()
        val saves = mutableListOf<AppSettings>()
        fun make(): FaceEnrollmentCoordinator = FaceEnrollmentCoordinator(
            store = store,
            embedder = FakeEmbedder(),
            faceFinder = { frame to face },
            settingsLoader = { settings },
            settingsSaver = { saves.add(it); settings = it },
        )
        val first = make().enroll("Alice").getOrThrow()
        val second = make().enroll("alice").getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals(1, saves.last().knownFaces.size)
        // Two samples merged: load still yields a unit vector.
        val centroid = store.load(first.id)!!
        val norm = kotlin.math.sqrt(centroid.fold(0.0) { acc, v -> acc + v.toDouble() * v })
        assertEquals(1.0, norm, 1e-6)
    }

    @Test
    fun blankLabelAndMissingFaceFailWithoutSaving() = runBlocking {
        val (c, saves) = coordinator(KnownFaceStore(tmp.newFolder("kf")), finder = { null })
        assertTrue(c.enroll("  ").isFailure)
        assertTrue(c.enroll("Alice").isFailure)
        assertTrue(saves.isEmpty())
    }

    @Test
    fun missingModelFailsFast() = runBlocking {
        val (c, saves) = coordinator(
            KnownFaceStore(tmp.newFolder("kf")),
            { frame to face },
            embedder = null,
        )
        assertTrue(c.enroll("Alice").isFailure)
        assertTrue(saves.isEmpty())
    }
}
