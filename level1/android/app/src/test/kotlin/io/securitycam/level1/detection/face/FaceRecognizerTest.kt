package io.securitycam.level1.detection.face

import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.GrayscaleBitmap
import io.securitycam.level1.identity.KnownFaceStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Known/unknown/plain-fallback behavior of the recognizing face detector. */
class FaceRecognizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val alice = KnownFace(id = "p1", label = "Alice")

    private fun frame(ts: Instant): AnalysisFrame = AnalysisFrame(
        timestamp = ts,
        bitmap = GrayscaleBitmap(3, 3, ByteArray(9)),
        color = ColorBitmap(100, 100, ByteArray(3 * 100 * 100)),
    )

    /** Returns the same vector for every face (or a per-call sequence). */
    private class FakeEmbedder(vararg vectors: FloatArray) : FaceEmbedder {
        private val queue = ArrayDeque(vectors.toList())
        override fun embed(frame: ColorBitmap, box: DoubleArray): FloatArray? =
            queue.removeFirstOrNull() ?: error("unexpected extra embed call")
    }

    private fun recognizer(
        engine: MockFaceEngine,
        embedder: FaceEmbedder?,
        people: List<KnownFace>,
        store: KnownFaceStore,
        matchThreshold: Double = 0.65,
        persistence: Int = 1,
    ): FaceRecognizer =
        FaceRecognizer(
            DetectorConfig(type = TriggerType.face, threshold = 0.5, persistenceFrames = persistence),
            store = store,
            embedder = embedder,
            peopleProvider = { people },
            matchThreshold = matchThreshold,
            engine = engine,
        )

    @Test
    fun knownMatchEmitsKnownWithLabelAndRoutingId() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        store.enroll("p1", floatArrayOf(1f, 0f, 0f))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9)) }
        val r = recognizer(engine, FakeEmbedder(floatArrayOf(1f, 0f, 0f)), listOf(alice), store)
            .analyzeFrameAsync(frame(base))
        assertTrue(r.triggered)
        assertEquals(TriggerType.faceKnown, r.triggerType)
        assertEquals(TriggerType.faceKnown, r.detectorId)
        assertEquals("Alice", r.detail)
    }

    @Test
    fun distantEmbeddingEmitsUnknown() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        store.enroll("p1", floatArrayOf(1f, 0f, 0f))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9)) }
        // Orthogonal vector: distance 1.0 > 0.65.
        val r = recognizer(engine, FakeEmbedder(floatArrayOf(0f, 1f, 0f)), listOf(alice), store)
            .analyzeFrameAsync(frame(base))
        assertTrue(r.triggered)
        assertEquals(TriggerType.faceUnknown, r.triggerType)
        assertEquals(TriggerType.faceUnknown, r.detectorId)
        assertNull(r.detail)
    }

    @Test
    fun nearestCentroidWins() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        store.enroll("p1", floatArrayOf(1f, 0f, 0f))
        val bob = KnownFace(id = "p2", label = "Bob")
        store.enroll("p2", floatArrayOf(0f, 1f, 0f))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9)) }
        // Unit vector near Bob's centroid (d=0.02) and far from Alice's
        // (d=0.8 > 0.65), so the nearest-centroid pick is unambiguous.
        val r = recognizer(
            engine,
            FakeEmbedder(floatArrayOf(0.2f, 0.9797959f, 0f)),
            listOf(alice, bob),
            store,
        ).analyzeFrameAsync(frame(base))
        assertEquals("Bob", r.detail)
    }

    @Test
    fun fallsBackToPlainFaceWithoutModelOrPeople() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9)) }
        val noModel = recognizer(engine, null, listOf(alice), store)
            .analyzeFrameAsync(frame(base))
        assertTrue(noModel.triggered)
        assertEquals(TriggerType.face, noModel.triggerType)
        assertNull(noModel.detectorId)

        val nobody = recognizer(engine, FakeEmbedder(floatArrayOf(1f, 0f, 0f)), emptyList(), store)
            .analyzeFrameAsync(frame(base))
        assertTrue(nobody.triggered)
        assertEquals(TriggerType.face, nobody.triggerType)
    }

    @Test
    fun persistenceGatesRecognitionLikePlainFace() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        store.enroll("p1", floatArrayOf(1f, 0f, 0f))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.9)) }
        val d = recognizer(
            engine,
            FakeEmbedder(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f)),
            listOf(alice),
            store,
            persistence = 2,
        )
        assertFalse(d.analyzeFrameAsync(frame(base)).triggered)
        val second = d.analyzeFrameAsync(frame(base.plusSeconds(1)))
        assertTrue(second.triggered)
        assertEquals(TriggerType.faceKnown, second.triggerType)
    }

    @Test
    fun lowScoreFaceNeverTriggers() = runBlocking {
        val store = KnownFaceStore(tmp.newFolder("kf"))
        store.enroll("p1", floatArrayOf(1f, 0f, 0f))
        val engine = MockFaceEngine().apply { faces.add(FaceDetection(0.1, 0.1, 0.4, 0.4, 0.3)) }
        val r = recognizer(engine, FakeEmbedder(floatArrayOf(1f, 0f, 0f)), listOf(alice), store)
            .analyzeFrameAsync(frame(base))
        assertFalse(r.triggered)
    }
}
