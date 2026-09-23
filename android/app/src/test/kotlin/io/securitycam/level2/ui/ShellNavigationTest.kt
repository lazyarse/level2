package io.securitycam.level2.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.SecurityCamApp
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.storage.RecordedEventRow
import io.securitycam.level2.ui.events.EventsViewModel
import io.securitycam.level2.ui.settings.SettingsViewModel
import io.securitycam.level2.ui.settings.sectionTag
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Port of the Flutter `shell_navigation_test.dart`: switching bottom tabs must
 * not recreate the Events screen state — the view-model (and its single
 * initial load) survives navigation away and back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Enough same-day rows to satisfy the pager's initial-fill threshold. */
    private fun freshRows(): List<RecordedEventRow> {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return (1..25).map { i ->
            RecordedEventRow(
                id = i.toLong(),
                timestamp = today.atTime(10, i).atZone(zone).toInstant(),
                cameraName = "Hallway",
                triggerType = "motion",
                score = 0.8,
                snapshotName = null,
                videoName = null,
                channelStatuses = emptyMap(),
                triggerTypes = emptyList(),
            )
        }
    }

    @Test
    fun eventsTabDoesNotRecreateItsStateOnNavigation() {
        var loads = 0
        val instances = mutableListOf<EventsViewModel>()
        val eventsFactory = viewModelFactory {
            initializer {
                val log = freshRows()
                EventsViewModel(
                    pageLoader = { start, end ->
                        loads++
                        log.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
                    },
                    floorLoader = { log.minOf { it.timestamp } },
                    snapshotLoader = { null },
                    videoOpener = null,
                ).also { instances.add(it) }
            }
        }
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                )
            }
        }

        compose.setContent {
            SecurityCamApp(
                eventsFactory = eventsFactory,
                settingsFactory = settingsFactory,
            )
        }
        compose.waitForIdle()
        assertEquals(0, instances.size)

        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()
        assertEquals(1, instances.size)
        assertEquals(1, loads)
        // List rows show detector icons only — no type-label text.
        compose.onAllNodesWithTag("eventDetectors_1_motion")
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("Motion", substring = false)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onAllNodesWithText("Confidence: High", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }

        compose.onNodeWithText("Monitor").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()

        assertEquals("view-model must survive tab switches", 1, instances.size)
        assertEquals("initial load must not re-run on return", 1, loads)
        compose.onAllNodesWithTag("eventDetectors_1_motion")
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("Motion", substring = false)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onAllNodesWithText("Confidence: High", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun settingsAppWithBack() {
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                )
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner()) {
                SecurityCamApp(settingsFactory = settingsFactory)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
    }

    @Test
    fun systemBackDismissesAlertLogViewer() {
        settingsAppWithBack()

        compose.onNodeWithTag(sectionTag("Advanced")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("openAlertLog").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithTag(sectionTag("Advanced")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackDismissesZoneEditor() {
        settingsAppWithBack()

        compose.onNodeWithTag(sectionTag("Zones")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No zones — detecting everywhere").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Detection zones").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Detection zones").assertDoesNotExist()
    }

    private class HangingCoordinator(app: android.app.Application) :
        io.securitycam.level2.identity.FaceEnrollmentCoordinator(
            store = io.securitycam.level2.identity.KnownFaceStore(
                java.io.File(app.filesDir, "kf-hang-${System.nanoTime()}"),
            ),
            embedder = null,
            faceFinder = io.securitycam.level2.identity.FaceFinder { null },
            settingsLoader = { AppSettings() },
            settingsSaver = { },
        ) {
        override suspend fun enroll(label: String): Result<io.securitycam.level2.core.KnownFace> {
            kotlinx.coroutines.awaitCancellation()
        }
    }

    @Test
    fun systemBackCancelsEnrollment() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val instances = mutableListOf<SettingsViewModel>()
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                    enrollmentFactory = { _ -> HangingCoordinator(app) },
                    cameraActive = { true },
                ).also { instances.add(it) }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner()) {
                SecurityCamApp(settingsFactory = settingsFactory)
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { instances.single().startEnrollment("Bob") }
        compose.waitForIdle()
        compose.onNodeWithText("Enrol face").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Enrol face").assertDoesNotExist()
        // Same path as the Cancel button: the job is cancelled and the
        // ViewModel reports it (the snackbar host lives on the Settings
        // screen, covered by the send-test snackbar test).
        org.junit.Assert.assertEquals(
            "Enrollment cancelled",
            instances.single().message.value,
        )
    }

    /** Parks in the REVIEW phase (finder immediate + confirm hook awaiting). */
    private class ReviewParkedCoordinator(
        app: android.app.Application,
        private val hooks: io.securitycam.level2.ui.settings.EnrollmentHooks,
    ) : io.securitycam.level2.identity.FaceEnrollmentCoordinator(
        store = io.securitycam.level2.identity.KnownFaceStore(
            java.io.File(app.filesDir, "kf-review-${System.nanoTime()}"),
        ),
        embedder = object : io.securitycam.level2.detection.face.FaceEmbedder {
            override fun embed(
                f: io.securitycam.level2.detection.ColorBitmap,
                box: DoubleArray,
            ): FloatArray = floatArrayOf(1f, 0f)
        },
        faceFinder = {
            io.securitycam.level2.detection.ColorBitmap(8, 8, ByteArray(192)) to
                io.securitycam.level2.detection.face.FaceDetection(0.1, 0.1, 0.5, 0.5, 0.9)
        },
        settingsLoader = { AppSettings() },
        settingsSaver = { },
        onCapture = hooks.onCapture,
        confirm = hooks.confirm,
        onNoFace = hooks.onNoFace,
    )

    @Test
    fun systemBackDuringReviewCancelsEnrollment() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val instances = mutableListOf<SettingsViewModel>()
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                    enrollmentFactory = { hooks -> ReviewParkedCoordinator(app, hooks) },
                    cameraActive = { true },
                ).also { instances.add(it) }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner()) {
                SecurityCamApp(settingsFactory = settingsFactory)
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { instances.single().startEnrollment("Bea") }
        compose.waitForIdle()
        // Finder is immediate: capture lands in REVIEW (confirm parks on the gate).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("usePhotoButton").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Enrol face").assertIsDisplayed()
        compose.onNodeWithTag("usePhotoButton").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("enrollmentPreview").assertDoesNotExist()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Enrol face").assertDoesNotExist()
        org.junit.Assert.assertEquals(
            "Enrollment cancelled",
            instances.single().message.value,
        )
        org.junit.Assert.assertEquals(null, instances.single().capturedFrame.value)
    }

    /** addSample that waits on a gate so the overlay spans real frames. */
    private class GateCoordinator(
        app: android.app.Application,
        private val face: io.securitycam.level2.core.KnownFace,
    ) : io.securitycam.level2.identity.FaceEnrollmentCoordinator(
        store = io.securitycam.level2.identity.KnownFaceStore(
            java.io.File(app.filesDir, "kf-gate-${System.nanoTime()}"),
        ),
        embedder = null,
        faceFinder = io.securitycam.level2.identity.FaceFinder { null },
        settingsLoader = { AppSettings() },
        settingsSaver = { },
    ) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun addSample(id: String): Result<io.securitycam.level2.core.KnownFace> {
            gate.await()
            return Result.success(face)
        }
    }

    @Test
    fun addPhotoKeepsFaceCardExpanded() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.CAMERA,
        )
        val face = io.securitycam.level2.core.KnownFace(id = "face_keep", label = "Keep")
        val initial = with(AppSettings) {
            AppSettings().copyWith(knownFaces = listOf(face)).withFaceRecognition(true)
        }
        val instances = mutableListOf<SettingsViewModel>()
        val coordinators = mutableListOf<GateCoordinator>()
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { initial },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                    enrollmentFactory = { _ ->
                        GateCoordinator(app, face).also { coordinators.add(it) }
                    },
                    cameraActive = { true },
                ).also { instances.add(it) }
            }
        }
        compose.setContent { SecurityCamApp(settingsFactory = settingsFactory) }
        compose.waitForIdle()

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(sectionTag("Detectors")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("addSample_face_keep").performScrollTo().assertIsDisplayed()

        compose.runOnIdle { instances.single().startSampleCapture(face) }
        compose.waitForIdle()
        // The overlay must actually render (intermediate composition) —
        // otherwise the round trip below proves nothing.
        compose.onNodeWithText("Enrol face").assertIsDisplayed()

        compose.runOnIdle { coordinators.single().gate.complete(Unit) }
        compose.waitForIdle()

        // The enrollment overlay came and went; the face card must still be
        // open (regression: the old swap-instead-of-stack rebuild collapsed
        // every section on return).
        org.junit.Assert.assertEquals(
            "Added photo for Keep",
            instances.single().message.value,
        )
        compose.onNodeWithTag("addSample_face_keep").performScrollTo().assertIsDisplayed()
    }

    private fun backOwner(): OnBackPressedDispatcherOwner {
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        return object : OnBackPressedDispatcherOwner, LifecycleOwner by lifecycleOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
                .also { backDispatcher = it }
        }
    }
}
