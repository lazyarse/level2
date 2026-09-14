package io.securitycam.level2.monitor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.DetectorRegistry
import io.securitycam.level2.detection.MotionDetector
import io.securitycam.level2.detection.audio.AudioEventClassifier
import io.securitycam.level2.detection.audio.MockAudioEventClassifier
import io.securitycam.level2.detection.face.FaceDetection
import io.securitycam.level2.detection.face.FaceEmbedder
import io.securitycam.level2.detection.face.FaceRecognizer
import io.securitycam.level2.detection.face.MockFaceEngine
import io.securitycam.level2.identity.FaceDirectory
import io.securitycam.level2.identity.KnownFaceStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 4: overlapping runtimes keep independent detector registries and face
 * rosters — creation must not mutate the process-global [DetectorRegistry] or
 * [FaceDirectory], and stop() must drop the runtime's own state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringRuntimeIsolationTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class FakeEmbedder : FaceEmbedder {
        override fun embed(frame: ColorBitmap, box: DoubleArray): FloatArray? =
            floatArrayOf(1f, 0f, 0f)
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun motionOnlySettings(): AppSettings = AppSettings.defaults().copy(
        detectorConfigs = mapOf(
            TriggerType.motion to DetectorConfig(
                type = TriggerType.motion,
                enabled = true,
                threshold = 0.01,
                persistenceFrames = 1,
            ),
        ),
    )

    private fun recognitionSettings(vararg faces: KnownFace): AppSettings =
        with(AppSettings) { motionOnlySettings().withFaceRecognition(true) }
            .copy(knownFaces = faces.toList())

    private suspend fun create(
        settings: AppSettings,
        faceStore: KnownFaceStore = KnownFaceStore(File(app.filesDir, "kf-${System.nanoTime()}")),
        faceEngine: MockFaceEngine = MockFaceEngine(),
        classifierLoader: suspend (Context) -> AudioEventClassifier = { MockAudioEventClassifier() },
    ): MonitoringRuntime = MonitoringRuntime.create(
        context = app,
        settings = settings,
        scope = scope(),
        faceStoreFactory = { faceStore },
        embedderLoader = { FakeEmbedder() },
        classifierLoader = classifierLoader,
        faceEngineFactory = { faceEngine },
    )

    @Test
    fun freshRegistriesAreIndependentCopies() = runBlocking {
        val defaults = DetectorRegistry.withDefaults()
        val runtime = create(motionOnlySettings())
        try {
            // Same shipped types, distinct maps: mutating one cannot leak.
            assertNotNull(defaults.factoryFor(TriggerType.face))
            assertTrue(defaults.supports(TriggerType.face))
            assertNotSame(
                defaults,
                runtime.detectorRegistry,
            )
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun overlappingRuntimesKeepIndependentRegistries() = runBlocking {
        val a = create(motionOnlySettings())
        val b = create(motionOnlySettings())
        try {
            assertNotSame(a.detectorRegistry, b.detectorRegistry)
            val fakeFactory: (DetectorConfig) -> io.securitycam.level2.detection.Detector =
                { c -> MotionDetector(c) }
            a.detectorRegistry.register("wave4-probe", fakeFactory)
            assertSame(fakeFactory, a.detectorRegistry.factoryFor("wave4-probe"))
            assertNull(b.detectorRegistry.factoryFor("wave4-probe"))
        } finally {
            a.stop()
            b.stop()
        }
        // stop() drops the runtime's face override.
        assertNull(a.detectorRegistry.factoryFor(TriggerType.face))
    }

    @Test
    fun overlappingRuntimesKeepIndependentRosters() = runBlocking {
        val alice = KnownFace(id = "face_alice", label = "Alice")
        val bob = KnownFace(id = "face_bob", label = "Bob")
        val centroid = floatArrayOf(1f, 0f, 0f)
        val storeA = KnownFaceStore(File(app.filesDir, "kf-a-${System.nanoTime()}"))
        val storeB = KnownFaceStore(File(app.filesDir, "kf-b-${System.nanoTime()}"))
        storeA.enroll(alice.id, centroid)
        storeB.enroll(bob.id, centroid)
        val engineA = MockFaceEngine()
        val engineB = MockFaceEngine()

        val a = create(recognitionSettings(alice), faceStore = storeA, faceEngine = engineA)
        val b = create(recognitionSettings(bob), faceStore = storeB, faceEngine = engineB)
        try {
            assertEquals(listOf(alice), a.faceRoster)
            assertEquals(listOf(bob), b.faceRoster)

            // Post-creation global churn (an enrollment landing mid-session)
            // must not move either live snapshot.
            FaceDirectory.setAll(listOf(KnownFace(id = "face_eve", label = "Eve")))
            assertEquals(listOf(alice), a.faceRoster)
            assertEquals(listOf(bob), b.faceRoster)

            // Behavioral proof with fake engines: each runtime's scoped face
            // factory recognizes its own roster, not the other's.
            val seen = FaceDetection(0.1, 0.1, 0.5, 0.5, 0.95)
            engineA.faces.add(seen)
            engineB.faces.add(seen)
            val frame = frame()
            val config = DetectorConfig(
                type = TriggerType.face,
                threshold = 0.0,
                persistenceFrames = 1,
            )
            val resultA = (a.detectorRegistry.factoryFor(TriggerType.face)!!(config) as FaceRecognizer)
                .analyzeFrameAsync(frame)
            val resultB = (b.detectorRegistry.factoryFor(TriggerType.face)!!(config) as FaceRecognizer)
                .analyzeFrameAsync(frame)
            assertEquals(TriggerType.faceKnown, resultA.triggerType)
            assertEquals("Alice", resultA.detail)
            assertEquals(TriggerType.faceKnown, resultB.triggerType)
            assertEquals("Bob", resultB.detail)

            // Stopping one runtime clears its snapshot and leaves the other.
            a.stop()
            assertTrue(a.faceRoster.isEmpty())
            assertEquals(listOf(bob), b.faceRoster)
        } finally {
            FaceDirectory.setAll(emptyList())
            runCatching { a.stop() }
            runCatching { b.stop() }
        }
    }

    private fun frame(): AnalysisFrame {
        val color = ColorBitmap(16, 16, ByteArray(3 * 16 * 16))
        return AnalysisFrame(
            timestamp = Instant.parse("2026-01-01T12:00:00Z"),
            bitmap = color.toGrayscale(),
            color = color,
        )
    }

    @Test
    fun skipsAudioClassifierLoadWhenNoAudioDetectorLive() = runBlocking {
        var loads = 0
        val runtime = create(
            motionOnlySettings(),
            classifierLoader = { loads++; MockAudioEventClassifier() },
        )
        try {
            assertEquals(0, loads)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun loadsAudioClassifierWhenAudioDetectorLive() = runBlocking {
        var loads = 0
        val settings = motionOnlySettings().copy(
            detectorConfigs = motionOnlySettings().detectorConfigs + (
                TriggerType.babyCry to DetectorConfig(
                    type = TriggerType.babyCry,
                    enabled = true,
                    threshold = 0.5,
                    persistenceFrames = 1,
                )
            ),
        )
        val runtime = create(
            settings,
            classifierLoader = { loads++; MockAudioEventClassifier() },
        )
        try {
            assertEquals(1, loads)
        } finally {
            runtime.stop()
        }
    }
}
